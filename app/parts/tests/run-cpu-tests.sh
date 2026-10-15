#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

test_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
tree_root=$(cd -- "$test_dir/../../../../../.." && pwd)
test_output=$(mktemp -d "${TMPDIR:-/tmp}/galaxian-cpu-tests.XXXXXX")
trap 'rm -rf -- "$test_output"' EXIT
java_root=${JAVA_HOME:-$tree_root/prebuilts/jdk/jdk21/linux-x86}

JAVA_HOME="$java_root" "$tree_root/external/kotlinc/bin/kotlinc" \
    "$test_dir/../src/org/lineageos/settings/galaxian/cpu/CpuControlManager.kt" \
    "$test_dir/CpuControlManagerTest.kt" \
    -include-runtime -d "$test_output/cpu-tests.jar"
"$java_root/bin/java" -jar "$test_output/cpu-tests.jar"
