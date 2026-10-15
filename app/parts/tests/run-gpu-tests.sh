#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

test_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
tree_root=$(cd -- "$test_dir/../../../../../.." && pwd)
test_output=$(mktemp -d "${TMPDIR:-/tmp}/galaxian-gpu-tests.XXXXXX")
trap 'rm -rf -- "$test_output"' EXIT
java_root=${JAVA_HOME:-$tree_root/prebuilts/jdk/jdk21/linux-x86}

JAVA_HOME="$java_root" "$tree_root/external/kotlinc/bin/kotlinc" \
    "$test_dir/../src/org/lineageos/settings/galaxian/gpu/GpuControlManager.kt" \
    "$test_dir/../src/org/lineageos/settings/galaxian/gpu/GpuControlSession.kt" \
    "$test_dir/GpuControlManagerTest.kt" \
    -include-runtime -d "$test_output/gpu-tests.jar"
"$java_root/bin/java" -jar "$test_output/gpu-tests.jar"
