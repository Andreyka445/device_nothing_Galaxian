#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check the SELinux access needed by Android hardware volume-key dispatch.

Uses an offline, compiled Android policy; does not read or change host policy.
The optional baseline must deny media_session lookup, reproducing the policy
failure that made MediaSessionManager dereference a null service binder.
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "thermal" / "tests"))
from check_property_access import OfflinePolicy


def resolve_service(paths, name):
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
    failures = []

    def require(policy, name, target, klass, permission, expected):
        actual, reason = policy.allowed(app, target, klass, permission)
        passed = actual == expected
        print(f"{'PASS' if passed else 'FAIL'} {name}: {klass}:{permission} "
              f"{'ALLOW' if actual else 'DENY'} reason={reason}")
        if not passed:
            failures.append(name)

    # Use the actual service names looked up by PhoneWindow and the framework.
    audio = resolve_service(args.service_contexts, "audio")
    media = resolve_service(args.service_contexts, "media_session")
    if args.baseline_policy:
        old = OfflinePolicy(args.baseline_policy, args.libsepol)
        require(old, "baseline MediaSession lookup", media, "service_manager", "find", False)

    policy = OfflinePolicy(args.policy, args.libsepol)
    for name, target in (("AudioManager", audio), ("MediaSessionManager", media)):
        require(policy, name, target, "service_manager", "find", True)
        # Lookup does not grant registration/replacement of either service.
        require(policy, name + " cannot register service", target,
                "service_manager", "add", False)
    for permission in ("call", "transfer"):
        require(policy, "system_server Binder", "u:r:system_server:s0",
                "binder", permission, True)
    # Retain the narrow service allowlist of this hardware-control app domain.
    require(policy, "unlabelled services remain inaccessible",
            "u:object_r:default_android_service:s0", "service_manager", "find", False)
    if failures:
        raise SystemExit(f"Volume-key access checks failed: {failures}")
    print("All volume-key framework service access checks passed.")


if __name__ == "__main__":
    main()
