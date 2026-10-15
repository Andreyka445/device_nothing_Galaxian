#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check effective thermal init/property access against an OFFLINE binary policy.

Requires Python3 and libsepol.so. No SELinux host/kernel policy is read or loaded.
Pass all relevant Android property_contexts inputs, including system_ext/device
contexts; exact matches take precedence over longest prefix matches.
"""
import argparse
import ctypes as C
import ctypes.util
import re
from pathlib import Path


class Decision(C.Structure):
    _fields_ = [(name, C.c_uint32) for name in
                ("allowed", "decided", "auditallow", "auditdeny", "seqno")]


class OfflinePolicy:
    def __init__(self, path, library=None):
        library = str(library) if library else ctypes.util.find_library("sepol")
        if not library:
            raise RuntimeError("Install the host libsepol runtime for this offline check")
        self.lib = C.CDLL(library)
        libc = C.CDLL(None)
        libc.fopen.argtypes = [C.c_char_p, C.c_char_p]
        libc.fopen.restype = C.c_void_p
        libc.fclose.argtypes = [C.c_void_p]
        self.lib.sepol_set_policydb_from_file.argtypes = [C.c_void_p]
        self.lib.sepol_set_policydb_from_file.restype = C.c_int
        fp = libc.fopen(str(path).encode(), b"rb")
        if not fp:
            raise RuntimeError(f"Cannot open policy {path}")
        try:
            # This libsepol function populates a userspace policydb only.
            if self.lib.sepol_set_policydb_from_file(fp) != 0:
                raise RuntimeError(f"Cannot parse policy {path}")
        finally:
            libc.fclose(fp)
        self.lib.sepol_context_to_sid.argtypes = [C.c_char_p, C.c_size_t, C.POINTER(C.c_uint32)]
        self.lib.sepol_string_to_security_class.argtypes = [C.c_char_p, C.POINTER(C.c_uint16)]
        self.lib.sepol_string_to_av_perm.argtypes = [C.c_uint16, C.c_char_p, C.POINTER(C.c_uint32)]
        self.lib.sepol_compute_av_reason.argtypes = [
            C.c_uint32, C.c_uint32, C.c_uint16, C.c_uint32,
            C.POINTER(Decision), C.POINTER(C.c_uint)]

    def allowed(self, source, target, klass="file", permission="read"):
        def sid(context):
            result = C.c_uint32()
            encoded = context.encode()
            if self.lib.sepol_context_to_sid(encoded, len(encoded) + 1, C.byref(result)) != 0:
                raise RuntimeError(f"Invalid policy context: {context}")
            return result
        cls = C.c_uint16()
        perm = C.c_uint32()
        if self.lib.sepol_string_to_security_class(klass.encode(), C.byref(cls)) != 0:
            raise RuntimeError(f"Unknown class {klass}")
        if self.lib.sepol_string_to_av_perm(cls, permission.encode(), C.byref(perm)) != 0:
            raise RuntimeError(f"Unknown permission {permission}")
        decision, reason = Decision(), C.c_uint()
        if self.lib.sepol_compute_av_reason(sid(source), sid(target), cls, perm,
                                            C.byref(decision), C.byref(reason)) != 0:
            raise RuntimeError("Cannot compute access decision")
        return decision.allowed & perm.value == perm.value, reason.value


def read_contexts(paths):
    entries = []
    for path in paths:
        for number, line in enumerate(path.read_text().splitlines(), 1):
            fields = line.split("#", 1)[0].split()
            if len(fields) < 2:
                continue
            kind = fields[2] if len(fields) > 2 and fields[2] in ("exact", "prefix") else "prefix"
            name = "" if fields[0] == "*" else fields[0]
            entries.append((name, kind, fields[1], f"{path}:{number}"))
    return entries


def resolve(entries, name):
    matches = [entry for entry in entries if
               (entry[1] == "exact" and entry[0] == name) or
               (entry[1] == "prefix" and name.startswith(entry[0]))]
    if not matches:
        raise RuntimeError(f"No property context for {name}")
    matches.sort(key=lambda e: (e[1] == "exact", len(e[0])), reverse=True)
    best = matches[0]
    ties = [e for e in matches if (e[1] == "exact", len(e[0])) ==
            (best[1] == "exact", len(best[0]))]
    if any(e[2] != best[2] for e in ties):
        raise RuntimeError(f"Conflicting contexts for {name}: {ties}")
    return best


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--policy", type=Path, required=True)
    parser.add_argument("--rc", type=Path, required=True)
    parser.add_argument("--libsepol", type=Path, help="Android host library for newer policy capabilities")
    parser.add_argument("--property-contexts", type=Path, action="append", required=True)
    args = parser.parse_args()
    policy, entries = OfflinePolicy(args.policy, args.libsepol), read_contexts(args.property_contexts)
    properties = set(re.findall(r"property:([^=\s]+)=", args.rc.read_text()))
    assert "init.svc.thermal_core" in properties, "Thermal daemon restart trigger is missing"
    failures = []

    def require(domain, name, klass="file", permission="read", expected=True):
        entry = resolve(entries, name)
        actual, reason = policy.allowed(f"u:r:{domain}:s0", entry[2], klass, permission)
        print(f"{'PASS' if actual == expected else 'FAIL'} {domain} {klass}:{permission} "
              f"{name} -> {entry[2]} {'ALLOW' if actual else 'DENY'} reason={reason}")
        print(f"  resolved from {entry[3]} ({entry[1]})")
        if actual != expected:
            failures.append((domain, name, permission))

    for name in sorted(properties):
        require("vendor_init", name)
    for name in ("sys.galaxianparts.thermal_disabled", "init.svc.thermal_core"):
        require("galaxian_parts_thermal", name)
    for suffix in ("thermal_state", "thermal_error", "thermal_errno", "thermal_profile", "thermal_result_id"):
        name = "vendor.galaxianparts." + suffix
        require("galaxian_parts_app", name)
        require("galaxian_parts_thermal", name, "property_service", "set")
    require("galaxian_parts_app", "sys.galaxianparts.thermal_disabled", "property_service", "set")
    require("vendor_init", "sys.galaxianparts.thermal_disabled", "property_service", "set")
    require("init", "init.svc.thermal_core", "property_service", "set")
    require("vendor_init", "init.svc.thermal_core", "property_service", "set", expected=False)

    # Reproduce the old runtime failure without changing the compiled policy:
    # remove only the exact context, then query the resolved generic prefix.
    legacy = [entry for entry in entries if not
              (entry[0] == "init.svc.thermal_core" and entry[1] == "exact")]
    previous = resolve(legacy, "init.svc.thermal_core")
    old_allowed, reason = policy.allowed("u:r:vendor_init:s0", previous[2])
    print(f"{'PASS' if not old_allowed else 'FAIL'} regression: generic init.svc.thermal_core -> "
          f"{previous[2]} {'ALLOW' if old_allowed else 'DENY'} reason={reason}")
    if old_allowed:
        failures.append(("vendor_init", "legacy init.svc prefix", "read"))
    if failures:
        raise SystemExit(f"Effective policy checks failed: {failures}")
    print("All thermal init trigger and property decisions passed against offline binary policy.")


if __name__ == "__main__":
    main()
