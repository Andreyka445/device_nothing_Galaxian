#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

test_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
tree_root=$(cd -- "$test_dir/../../../../../../.." && pwd)
source_dir="$test_dir/../../src/org/lineageos/settings/galaxian/adaptive"
test_output=$(mktemp -d "${TMPDIR:-/tmp}/galaxian-frame-tests.XXXXXX")
trap 'rm -rf -- "$test_output"' EXIT
java_root=${JAVA_HOME:-$tree_root/prebuilts/jdk/jdk21/linux-x86}

JAVA_HOME="$java_root" "$tree_root/external/kotlinc/bin/kotlinc" \
    "$source_dir/FrameTelemetryModels.kt" \
    "$source_dir/FrameTelemetryParser.kt" \
    "$test_dir/FrameTelemetryTest.kt" \
    -include-runtime -d "$test_output/frame-tests.jar"
"$java_root/bin/java" -jar "$test_output/frame-tests.jar" "$test_dir/fixtures"

# Compile the Android reader against the real platform APIs when available.
framework_jar=${FRAMEWORK_JAR:-$tree_root/out/soong/.intermediates/frameworks/base/framework-minus-apex/android_common/combined/framework.jar}
if [[ -f "$framework_jar" ]]; then
    JAVA_HOME="$java_root" "$tree_root/external/kotlinc/bin/kotlinc" \
        "$source_dir"/FrameTelemetry*.kt \
        -classpath "$framework_jar:$tree_root/prebuilts/sdk/current/public/android.jar" \
        -d "$test_output/frame-reader.jar"
    echo 'Frame telemetry Android platform compilation: PASS'
else
    echo 'Frame telemetry Android platform compilation: SKIPPED (set FRAMEWORK_JAR)'
fi
