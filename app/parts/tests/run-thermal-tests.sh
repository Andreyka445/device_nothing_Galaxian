#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

test_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
tree_root=$(cd -- "$test_dir/../../../../../.." && pwd)
test_output=$(mktemp -d "${TMPDIR:-/tmp}/galaxian-thermal-tests.XXXXXX")
trap 'rm -rf -- "$test_output"' EXIT
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Werror -pthread \
    -DGALAXIAN_THERMAL_HOST_TEST \
    "$test_dir/../thermal/ThermalControl.cpp" \
    "$test_dir/../thermal/ThermalControlTest.cpp" \
    -o "$test_output/thermal-tests"
"$test_output/thermal-tests" \
    "$tree_root/vendor/nothing/Galaxian/proprietary/vendor/etc/thermal/disable_throttling.conf" \
    "$tree_root/vendor/nothing/Galaxian/proprietary/vendor/etc/thermal/disable_thermal.conf"
