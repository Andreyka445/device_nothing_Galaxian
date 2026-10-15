// SPDX-License-Identifier: Apache-2.0
#include "ThermalControl.h"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <poll.h>
#include <string>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <thread>
#include <unistd.h>

#ifndef GALAXIAN_THERMAL_HOST_TEST
#include <android-base/logging.h>
#include <android-base/properties.h>
#endif

namespace galaxian::thermal {
namespace {
constexpr char kDisabledProfile[] = "disable_throttling.conf";
constexpr char kDefaultProfile[] = "thermal.conf";

class Fd {
  public:
    explicit Fd(int value) : value_(value) {}
    ~Fd() { if (value_ >= 0) close(value_); }
    operator int() const { return value_; }
  private:
    int value_;
};

struct FileResult {
    std::string value;
    int error = 0;
};

FileResult ReadFile(const std::string& path, size_t maximum, bool lock = false) {
    Fd fd(open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK));
    if (fd < 0) return {{}, errno};
    if (lock && flock(fd, LOCK_SH | LOCK_NB) != 0) return {{}, errno};
    struct stat info {};
    if (fstat(fd, &info) != 0) return {{}, errno};
    if (!S_ISREG(info.st_mode)) return {{}, EINVAL};
    std::string result(maximum + 1, '\0');
    ssize_t count;
    do { count = read(fd, result.data(), result.size()); } while (count < 0 && errno == EINTR);
    if (count < 0) return {{}, errno};
    if (static_cast<size_t>(count) > maximum) return {{}, EOVERFLOW};
    result.resize(count);
    return {result, 0};
}

FileResult ReadProfile(const std::string& path) {
    FileResult result = ReadFile(path, 64, true);
    if (result.error) return result;
    while (!result.value.empty() && (result.value.back() == '\n' || result.value.back() == '\r'))
        result.value.pop_back();
    if (!IsProfileName(result.value)) return {{}, EINVAL};
    return result;
}

FileResult WaitForCurrent(const Paths& paths, int timeout_ms, const std::string& expected = {}) {
    FileResult last;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
    do {
        last = ReadProfile(paths.current);
        if (!last.error && (expected.empty() || last.value == expected)) return last;
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    } while (std::chrono::steady_clock::now() < deadline);
    return last;
}

int SavePrevious(const std::string& path, const std::string& value) {
    const FileResult previous = ReadProfile(path);
    if (!previous.error) return 0;
    if (previous.error != ENOENT) return previous.error;
    // Publish the snapshot atomically: init can restart us when requests change.
    // A partially written snapshot must never replace the valid original profile.
    const std::string temporary = path + ".tmp";
    Fd fd(open(temporary.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600));
    if (fd < 0) return errno;
    ssize_t count;
    do { count = write(fd, value.data(), value.size()); } while (count < 0 && errno == EINTR);
    if (count != static_cast<ssize_t>(value.size())) return count < 0 ? errno : EIO;
    if (fsync(fd) != 0) return errno;
    if (rename(temporary.c_str(), path.c_str()) != 0) return errno;
    return 0;
}

FileResult ReadSelectedProfile(const Paths& paths, const std::string& name) {
    // MTK tries /data/vendor/thermal first. Never silently fall back around an
    // existing but unreadable, symlinked, empty, or otherwise invalid override.
    const std::string custom = paths.custom_profiles + name;
    struct stat info {};
    if (lstat(custom.c_str(), &info) == 0) return ReadFile(custom, 65536);
    if (errno != ENOENT) return {{}, errno};
    return ReadFile(paths.profiles + name, 65536);
}

std::string CurrentError(int error) {
    if (error == ENOENT) return "current_missing";
    if (error == EACCES || error == EPERM) return "current_denied";
    if (error == EAGAIN || error == EWOULDBLOCK) return "current_busy";
    return "current_invalid";
}

Result Failure(const std::string& code, int error, const Paths& paths) {
    return {false, ReadProfile(paths.current).value, code, error};
}

Result SocketCommand(const Paths& paths, const std::string& command, int timeout_ms) {
    if (paths.socket.size() >= sizeof(sockaddr_un::sun_path))
        return {false, {}, "socket_connect_failed", EINVAL};
    Fd fd(socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0));
    if (fd < 0) return {false, {}, "socket_connect_failed", errno};
    sockaddr_un address {};
    address.sun_family = AF_UNIX;
    std::memcpy(address.sun_path, paths.socket.c_str(), paths.socket.size() + 1);
    if (connect(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
        const int error = errno;
        const char* code = error == ENOENT ? "socket_missing" :
            (error == EACCES || error == EPERM) ? "socket_denied" : "socket_connect_failed";
        return {false, {}, code, error};
    }
    if (!command.empty()) {
        ssize_t count;
        do { count = send(fd, command.data(), command.size(), MSG_NOSIGNAL); }
        while (count < 0 && errno == EINTR);
        if (count != static_cast<ssize_t>(command.size()))
            return {false, {}, "socket_send_failed", count < 0 ? errno : EIO};
    }
    if (shutdown(fd, SHUT_WR) != 0) return {false, {}, "socket_send_failed", errno};
    // thermal_core has no reply; it closes the connection after queuing the
    // parsed command. Wait for that close so startup cannot acknowledge a stale
    // .current_tp before the daemon has reached its accept loop.
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
    while (std::chrono::steady_clock::now() < deadline) {
        pollfd event {fd, POLLIN, 0};
        int polled;
        do { polled = poll(&event, 1, 100); } while (polled < 0 && errno == EINTR);
        if (polled < 0) return {false, {}, "socket_connect_failed", errno};
        if (polled == 0) continue;
        char unexpected;
        const ssize_t received = recv(fd, &unexpected, 1, 0);
        if (received == 0) return {true, {}, "none", 0};
        if (received < 0 && (errno == EAGAIN || errno == EINTR)) continue;
        return {false, {}, "socket_connect_failed", received < 0 ? errno : EPROTO};
    }
    return {false, {}, "socket_timeout", ETIMEDOUT};
}

Result SendProfile(const Paths& paths, const std::string& name, int timeout_ms) {
    if (!IsProfileName(name)) return {false, {}, "socket_send_failed", EINVAL};
    // Verified single receive (max128 bytes), parser "%50s %50s", command apply.
    std::string command = "apply " + name;
    command.push_back('\0');
    return SocketCommand(paths, command, timeout_ms);
}
}  // namespace

bool IsProfileName(const std::string& name) {
    if (name.empty() || name.size() > 49 || name.front() == '.' ||
        name.find("..") != std::string::npos || name.size() < 6 ||
        name.compare(name.size() - 5, 5, ".conf") != 0) return false;
    return std::all_of(name.begin(), name.end(), [](unsigned char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
               (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
    });
}

bool IsThrottlingOnlyProfile(const std::string& contents) {
    // MTK encodes each printable character with column modulo10, alphabet32..122.
    std::string decoded = contents;
    if (decoded.compare(0, 13, "[policy_type]") != 0) {
        size_t column = 0;
        for (char& c : decoded) {
            const unsigned char original = c;
            if (c == '\n') { column = 0; continue; }
            if (original >= 32 && original <= 122)
                c = 32 + (original - 32 + 91 - column % 10) % 91;
            ++column;
        }
    }
    decoded.erase(std::remove_if(decoded.begin(), decoded.end(), [](unsigned char c) {
        return c == ' ' || c == '\t' || c == '\r';
    }), decoded.end());
    return decoded.find("\npermanent=No\n") != std::string::npos &&
           decoded.find("\n[LTF-disable-throttling]\n") != std::string::npos &&
           decoded.find("\nHW_protection=enabled\n") != std::string::npos &&
           decoded.find("\nHW_protection=disabled") == std::string::npos;
}

Result ProbeDaemon(const Paths& paths, int timeout_ms) {
    // An empty, half-closed request takes the verified recv==0 -> sscanf==-1
    // path. It changes no policy, and its peer close confirms the daemon has
    // initialized. This is needed before reading its persistent .current_tp.
    return SocketCommand(paths, {}, timeout_ms);
}

Result SetDisabled(bool disabled, const Paths& paths, int timeout_ms) {
    const FileResult current = WaitForCurrent(paths, timeout_ms);
    if (current.error) return Failure(CurrentError(current.error), current.error, paths);

    if (disabled) {
        const FileResult profile = ReadSelectedProfile(paths, kDisabledProfile);
        if (profile.error) return Failure(profile.error == ENOENT ? "profile_missing" :
            (profile.error == EACCES || profile.error == EPERM) ? "profile_denied" : "profile_unsafe",
            profile.error, paths);
        if (!IsThrottlingOnlyProfile(profile.value)) return Failure("profile_unsafe", 0, paths);
        if (current.value == kDisabledProfile) return {true, current.value, "none", 0};
        const FileResult existing = ReadSelectedProfile(paths, current.value);
        if (existing.error || existing.value.empty()) return Failure("restore_missing", existing.error, paths);
        const int saved = SavePrevious(paths.previous, current.value);
        if (saved) return Failure("snapshot_failed", saved, paths);
        const Result sent = SendProfile(paths, kDisabledProfile, timeout_ms);
        if (!sent) return Failure(sent.error, sent.os_error, paths);
        const FileResult applied = WaitForCurrent(paths, timeout_ms, kDisabledProfile);
        if (!applied.error && applied.value == kDisabledProfile)
            return {true, applied.value, "none", 0};
        // A delayed/rejected apply must not leave an unconfirmed override.
        const FileResult previous = ReadProfile(paths.previous);
        if (!previous.error && SendProfile(paths, previous.value, timeout_ms))
            WaitForCurrent(paths, timeout_ms, previous.value);
        return Failure("apply_unconfirmed", applied.error, paths);
    }

    const FileResult previous = ReadProfile(paths.previous);
    if (previous.error && previous.error != ENOENT)
        return Failure("snapshot_failed", previous.error, paths);
    if (previous.error == ENOENT && current.value != kDisabledProfile)
        return {true, current.value, "none", 0};
    const std::string restore = previous.error == ENOENT ? kDefaultProfile : previous.value;
    const FileResult profile = ReadSelectedProfile(paths, restore);
    if (restore == kDisabledProfile || profile.error || profile.value.empty())
        return Failure("restore_missing", profile.error, paths);
    if (current.value != restore) {
        const Result sent = SendProfile(paths, restore, timeout_ms);
        if (!sent) return Failure(sent.error, sent.os_error, paths);
        const FileResult applied = WaitForCurrent(paths, timeout_ms, restore);
        if (applied.error || applied.value != restore)
            return Failure("restore_failed", applied.error, paths);
    }
    if (unlink(paths.previous.c_str()) != 0 && errno != ENOENT)
        return Failure("snapshot_failed", errno, paths);
    return {true, restore, "none", 0};
}
}  // namespace galaxian::thermal

#ifndef GALAXIAN_THERMAL_HOST_TEST
int main(int argc, char** argv) {
    (void)argc;
    android::base::InitLogging(argv, android::base::LogdLogger(android::base::SYSTEM));
    constexpr char kRequest[] = "sys.galaxianparts.thermal_disabled";
    constexpr char kState[] = "vendor.galaxianparts.thermal_state";
    const bool disabled = android::base::GetBoolProperty(kRequest, false);
    android::base::SetProperty(kState, "changing");
    const bool daemon_running = android::base::GetProperty("init.svc.thermal_core", "") == "running";
    auto result = daemon_running ? galaxian::thermal::ProbeDaemon({}) :
        galaxian::thermal::Result{false, {}, "daemon_stopped", 0};
    if (result) result = galaxian::thermal::SetDisabled(disabled, {});
    // A new request causes init to restart this helper. Never publish a stale result.
    if (android::base::GetBoolProperty(kRequest, false) != disabled) return 0;
    const char* state = result.profile.empty() ? "error" :
        result.profile == "disable_throttling.conf" ? "disabled" : "enabled";
    android::base::SetProperty("vendor.galaxianparts.thermal_profile", result.profile);
    android::base::SetProperty("vendor.galaxianparts.thermal_errno", std::to_string(result.os_error));
    android::base::SetProperty("vendor.galaxianparts.thermal_error", result.error);
    android::base::SetProperty(kState, state);
    constexpr char kResultId[] = "vendor.galaxianparts.thermal_result_id";
    const int previous_id = android::base::GetIntProperty<int>(kResultId, 0);
    const int result_id = previous_id >= std::numeric_limits<int>::max() ? 1 : previous_id + 1;
    android::base::SetProperty(kResultId, std::to_string(result_id));
    if (!result) {
        LOG(ERROR) << "Thermal request " << (disabled ? "disable" : "restore") << " failed: "
                   << result.error << ", errno=" << result.os_error << " ("
                   << std::strerror(result.os_error) << "), active profile=" << result.profile;
        return 1;
    }
    LOG(INFO) << "Thermal request confirmed: " << result.profile;
    return 0;
}
#endif
