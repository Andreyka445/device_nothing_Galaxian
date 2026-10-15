// SPDX-License-Identifier: Apache-2.0
// Host-only fake-daemon test; does not change properties or device thermal state.
#include "ThermalControl.h"

#include <cassert>
#include <cerrno>
#include <fcntl.h>
#include <sys/file.h>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <sys/socket.h>
#include <sys/un.h>
#include <thread>
#include <unistd.h>
#include <vector>

using namespace galaxian::thermal;
namespace fs = std::filesystem;

std::string Read(const std::string& path) {
    std::ifstream file(path);
    return std::string(std::istreambuf_iterator<char>(file), {});
}
void Write(const std::string& path, const std::string& value) { std::ofstream(path) << value; }

class Fixture {
  public:
    Paths paths;
    std::string directory;
    std::vector<std::string> commands;
    int listener;
    Fixture(const std::string& profile) {
        char buffer[] = "/tmp/galaxian-thermal-test-XXXXXX";
        directory = mkdtemp(buffer);
        paths.socket = directory + "/socket";
        paths.current = directory + "/current";
        paths.previous = directory + "/previous";
        paths.profiles = directory + "/profiles/";
        paths.custom_profiles = directory + "/custom/";
        fs::create_directory(paths.profiles);
        fs::create_directory(paths.custom_profiles);
        Write(paths.profiles + "disable_throttling.conf", profile);
        Write(paths.profiles + "thermal.conf", "stock");
        Write(paths.profiles + "thermal_policy_03.conf", "variant");
        Write(paths.current, "thermal_policy_03.conf");
        listener = socket(AF_UNIX, SOCK_STREAM, 0);
        assert(listener >= 0);
        sockaddr_un address {};
        address.sun_family = AF_UNIX;
        strcpy(address.sun_path, paths.socket.c_str());
        assert(bind(listener, reinterpret_cast<sockaddr*>(&address), sizeof(address)) == 0);
        assert(listen(listener, 4) == 0);
    }
    ~Fixture() { close(listener); fs::remove_all(directory); }
    std::thread Serve(int count, bool accept_changes = true) {
        return std::thread([this, count, accept_changes] {
            for (int i = 0; i < count; ++i) {
                int client = accept(listener, nullptr, nullptr);
                assert(client >= 0);
                char buffer[128] {};
                ssize_t length = read(client, buffer, sizeof(buffer));
                assert(length >= 0 && length < 128);
                if (length == 0) { commands.push_back("probe"); close(client); continue; }
                std::string command(buffer);
                assert(command.rfind("apply ", 0) == 0);
                commands.push_back(command.substr(6));
                if (accept_changes) Write(paths.current, command.substr(6));
                close(client);
            }
        });
    }
};

int main(int argc, char** argv) {
    assert(argc == 3);
    const std::string throttling = Read(argv[1]);
    const std::string unsafe = Read(argv[2]);
    assert(IsThrottlingOnlyProfile(throttling));
    assert(!IsThrottlingOnlyProfile(unsafe));
    assert(!IsThrottlingOnlyProfile("garbage"));
    for (const char* name : {"../thermal.conf", "a\napply b.conf", "/thermal.conf", ".conf", "a..conf", "a b.conf"})
        assert(!IsProfileName(name));
    assert(IsProfileName("thermal_policy_03.conf"));
    {
        Fixture fixture(throttling);
        auto server = fixture.Serve(1);
        assert(ProbeDaemon(fixture.paths, 500));
        server.join();
        assert(Read(fixture.paths.current) == "thermal_policy_03.conf");
        assert((fixture.commands == std::vector<std::string>{"probe"}));
    }
    {
        Fixture fixture(throttling);
        const Result probe = ProbeDaemon(fixture.paths, 50); // Socket exists, daemon has not accepted.
        assert(!probe && probe.error == "socket_timeout");
        assert(Read(fixture.paths.current) == "thermal_policy_03.conf");
    }
    {
        Fixture fixture(throttling);
        auto server = fixture.Serve(2);
        assert(SetDisabled(true, fixture.paths, 500));
        assert(Read(fixture.paths.previous) == "thermal_policy_03.conf");
        assert(SetDisabled(true, fixture.paths, 500)); // Idempotent; no second apply.
        assert(SetDisabled(false, fixture.paths, 500));
        server.join();
        assert(Read(fixture.paths.current) == "thermal_policy_03.conf");
        assert(!fs::exists(fixture.paths.previous));
        assert((fixture.commands == std::vector<std::string>{"disable_throttling.conf", "thermal_policy_03.conf"}));
    }
    {
        Fixture fixture(throttling);
        auto server = fixture.Serve(2, false);
        const Result rejected = SetDisabled(true, fixture.paths, 150);
        assert(!rejected && rejected.error == "apply_unconfirmed");
        assert(rejected.profile == "thermal_policy_03.conf");
        server.join();
        assert(fixture.commands.back() == "thermal_policy_03.conf"); // Roll back rejected/late apply.
    }
    {
        Fixture fixture(throttling);
        Write(fixture.paths.custom_profiles + "disable_throttling.conf", unsafe);
        const Result unsafe_result = SetDisabled(true, fixture.paths, 100);
        assert(!unsafe_result && unsafe_result.error == "profile_unsafe");
    }
    {
        Fixture fixture(throttling);
        Write(fixture.paths.custom_profiles + "disable_throttling.conf", "");
        assert(!SetDisabled(true, fixture.paths, 100)); // No unsafe fallback around a broken override.
    }
    {
        Fixture fixture(throttling);
        Write(fixture.paths.current, "disable_throttling.conf");
        auto server = fixture.Serve(1);
        assert(SetDisabled(false, fixture.paths, 500));
        server.join();
        assert(Read(fixture.paths.current) == "thermal.conf"); // Lost restore-point recovery.
    }
    {
        Fixture fixture(throttling);
        Write(fixture.paths.previous, "../forged.conf");
        assert(!SetDisabled(true, fixture.paths, 100));
    }
    {
        Fixture fixture(throttling);
        fs::remove(fixture.paths.current);
        const Result result = SetDisabled(true, fixture.paths, 50);
        assert(!result && result.error == "current_missing" && result.os_error == ENOENT);
        assert(result.profile.empty());
    }
    {
        Fixture fixture(throttling);
        int lock_fd = open(fixture.paths.current.c_str(), O_RDONLY);
        assert(lock_fd >= 0 && flock(lock_fd, LOCK_EX | LOCK_NB) == 0);
        const Result result = SetDisabled(true, fixture.paths, 50);
        assert(!result && result.error == "current_busy");
        close(lock_fd);
        Write(fixture.paths.previous + ".tmp", "crashed partial snapshot");
        auto server = fixture.Serve(1);
        assert(SetDisabled(true, fixture.paths, 500));
        server.join();
        assert(Read(fixture.paths.previous) == "thermal_policy_03.conf");
    }
    {
        Fixture fixture(throttling);
        fs::remove(fixture.paths.socket);
        const Result result = SetDisabled(true, fixture.paths, 50);
        assert(!result && result.error == "socket_missing" && result.os_error == ENOENT);
        assert(result.profile == "thermal_policy_03.conf");
    }
    {
        Fixture fixture(throttling);
        Write(fixture.paths.previous, "thermal_policy_03.conf");
        Write(fixture.paths.current, "disable_throttling.conf");
        auto server = fixture.Serve(1, false);
        const Result result = SetDisabled(false, fixture.paths, 50);
        server.join();
        assert(!result && result.error == "restore_failed");
        assert(result.profile == "disable_throttling.conf");
        assert(Read(fixture.paths.previous) == "thermal_policy_03.conf");
    }
    std::cout << "Thermal profile, acknowledgment, exact restore, rollback, atomic snapshot and diagnostic tests passed\n";
}
