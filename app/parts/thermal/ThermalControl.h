// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <string>

namespace galaxian::thermal {

struct Paths {
    std::string socket = "/dev/socket/thermal_socket";
    std::string current = "/data/vendor/thermal/.current_tp";
    std::string previous = "/data/vendor/galaxianparts/thermal_previous";
    std::string profiles = "/vendor/etc/thermal/";
    std::string custom_profiles = "/data/vendor/thermal/";
};

struct Result {
    bool success = false;
    std::string profile;
    std::string error = "none";
    int os_error = 0;
    explicit operator bool() const { return success; }
};

bool IsProfileName(const std::string& name);
bool IsThrottlingOnlyProfile(const std::string& contents);
Result ProbeDaemon(const Paths& paths, int timeout_ms = 5000);
Result SetDisabled(bool disabled, const Paths& paths, int timeout_ms = 5000);

}  // namespace galaxian::thermal
