#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

test_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
tree_root=$(cd -- "$test_dir/../../../../../../.." && pwd)
source_dir="$test_dir/../../src/org/lineageos/settings/galaxian"
test_output=$(mktemp -d "${TMPDIR:-/tmp}/galaxian-adaptive-tests.XXXXXX")
trap 'rm -rf -- "$test_output"' EXIT
java_root=${JAVA_HOME:-$tree_root/prebuilts/jdk/jdk21/linux-x86}

JAVA_HOME="$java_root" "$tree_root/external/kotlinc/bin/kotlinc" \
    "$source_dir/cpu/CpuControlManager.kt" \
    "$source_dir/cpu/CpuControlSession.kt" \
    "$source_dir/adaptive/FrameTelemetryModels.kt" \
    "$source_dir/adaptive/FrameTelemetryParser.kt" \
    "$source_dir/adaptive/AdaptiveEngine.kt" \
    "$source_dir/adaptive/AdaptiveTelemetry.kt" \
    "$test_dir/AdaptiveEngineTest.kt" \
    "$test_dir/CpuControlSessionTest.kt" \
    -include-runtime -d "$test_output/adaptive-tests.jar"
"$java_root/bin/java" -jar "$test_output/adaptive-tests.jar"
