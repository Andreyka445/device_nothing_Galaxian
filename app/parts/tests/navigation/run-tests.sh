#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

test_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
tree_root=$(cd -- "$test_dir/../../../../../../.." && pwd)
source_file="$test_dir/../../src/org/lineageos/settings/galaxian/PartsActivity.kt"
test_output=$(mktemp -d "${TMPDIR:-/tmp}/galaxian-navigation-tests.XXXXXX")
trap 'rm -rf -- "$test_output"' EXIT
java_root=${JAVA_HOME:-$tree_root/prebuilts/jdk/jdk21/linux-x86}

# Compile the production file itself: never extract or duplicate its lifecycle logic.
JAVA_HOME="$java_root" "$tree_root/external/kotlinc/bin/kotlinc" \
    "$source_file" "$test_dir"/stubs/*.kt "$test_dir/PartsNavigationTest.kt" \
    -nowarn -include-runtime -d "$test_output/navigation-tests.jar"
"$java_root/bin/java" -jar "$test_output/navigation-tests.jar"
