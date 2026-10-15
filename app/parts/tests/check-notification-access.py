#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check the framework access needed for adaptive foreground/proposal notifications.

Uses an offline compiled Android policy. The optional baseline must deny the
notification service lookup that prevented NotificationManager channel creation.
This checks SELinux access only; notification permission and channel visibility
remain framework/user decisions.
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "thermal" / "tests"))
from check_property_access import OfflinePolicy


def service_context(paths, name):
    contexts = set()
    for path in paths:
        for line in path.read_text().splitlines():
            fields = line.split("#", 1)[0].split()
            if len(fields) >= 2 and fields[0] == name:
                contexts.add(fields[1])
    if len(contexts) != 1:
        raise RuntimeError(f"Expected one service context for {name}: {contexts}")
    return contexts.pop()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--policy", required=True, type=Path)
    parser.add_argument("--baseline-policy", type=Path)
    parser.add_argument("--libsepol", type=Path)
    parser.add_argument("--service-contexts", required=True, action="append", type=Path)
    args = parser.parse_args()
    app = "u:r:galaxian_parts_app:s0"
    notification = service_context(args.service_contexts, "notification")
    activity = service_context(args.service_contexts, "activity")
    failures = []

    def require(policy, name, target, klass, permission, expected):
        actual, reason = policy.allowed(app, target, klass, permission)
        passed = actual == expected
        print(f"{'PASS' if passed else 'FAIL'} {name}: {klass}:{permission} "
              f"{'ALLOW' if actual else 'DENY'} reason={reason}")
        if not passed:
            failures.append(name)

    if args.baseline_policy:
        old = OfflinePolicy(args.baseline_policy, args.libsepol)
        require(old, "baseline NotificationManager lookup", notification,
                "service_manager", "find", False)

    policy = OfflinePolicy(args.policy, args.libsepol)
    for name, target in (("NotificationManager", notification),
                         ("ActivityManager foreground service", activity)):
        require(policy, name, target, "service_manager", "find", True)
        require(policy, name + " cannot register service", target,
                "service_manager", "add", False)
    for permission in ("call", "transfer"):
        require(policy, "system_server Binder", "u:r:system_server:s0",
                "binder", permission, True)
    require(policy, "unlabelled services remain inaccessible",
            "u:object_r:default_android_service:s0", "service_manager", "find", False)
    if failures:
        raise SystemExit(f"Notification access checks failed: {failures}")
    print("All adaptive notification framework service access checks passed.")


if __name__ == "__main__":
    main()
