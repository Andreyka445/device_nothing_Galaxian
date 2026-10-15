#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only GPU controls regression against an offline compiled Android policy."""
import argparse
import ctypes as C
from pathlib import Path
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--policy', type=Path, required=True)
parser.add_argument('--libsepol', type=Path, help='Android host libsepol for newer policy capabilities')
args = parser.parse_args()
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'thermal' / 'tests'))
from check_property_access import OfflinePolicy
policy = OfflinePolicy(args.policy,args.libsepol)
lib = policy.lib
lib.sepol_genfs_sid.argtypes = [C.c_char_p,C.c_char_p,C.c_uint16,C.POINTER(C.c_uint32)]
lib.sepol_sid_to_context.argtypes = [C.c_uint32,C.POINTER(C.c_char_p),C.POINTER(C.c_size_t)]
libc = C.CDLL(None)
libc.free.argtypes = [C.c_void_p]
failures = []
checks = 0


def genfs(path,klass='file'):
    cls = C.c_uint16()
    assert lib.sepol_string_to_security_class(klass.encode(),C.byref(cls)) == 0
    sid = C.c_uint32()
    assert lib.sepol_genfs_sid(b'proc',path.encode(),cls,C.byref(sid)) == 0
    context = C.c_char_p()
    length = C.c_size_t()
    assert lib.sepol_sid_to_context(sid,C.byref(context),C.byref(length)) == 0
    try:
        return context.value.decode()
    finally:
        libc.free(context)


def expect(description,actual,want):
    global checks
    checks += 1
    if actual != want:
        failures.append(f'{description}: got {actual}, expected {want}')


def permission(domain,context,klass,perm,want):
    allowed,_ = policy.allowed('u:r:'+domain+':s0',context,klass,perm)
    expect(f'{domain} -> {context} {klass}:{perm}',allowed,want)


paths = {'gpufreq_status':'vendor_galaxian_gpu_status',
         'gpu_working_opp_table':'vendor_galaxian_gpu_status',
         'fix_target_opp_index':'vendor_galaxian_gpu_control',
         'limit_table':'vendor_galaxian_gpu_range'}
for node,typ in paths.items():
    context = genfs('/gpufreqv2/'+node)
    expect(node+' compiled genfs label',context,'u:object_r:'+typ+':s0')
    for perm in ['getattr','open','read']:
        permission('galaxian_parts_app',context,'file',perm,True)
    permission('galaxian_parts_app',context,'file','write',node in ['fix_target_opp_index', 'limit_table'])
    permission('galaxian_parts_app',context,'file','setattr',False)
    for perm in ['getattr','open','read','write','append','lock','map','ioctl']:
        permission('hal_power_default',context,'file',perm,True)
    permission('thermal_core',context,'file','read',True)
    permission('thermal_core',context,'file','write',False)
    for perm in ['open','read','setattr']:
        permission('vendor_init',context,'file',perm,True)
    permission('vendor_init',context,'file','write',False)
    for domain in ['mtk_hal_power','untrusted_app','system_app']:
        permission(domain,context,'file','write',False)
for node in ['fix_custom_freq_volt','mfgsys_power_control',
             'mfgsys_config','gpu_signed_opp_table','asensor_info']:
    context = genfs('/gpufreqv2/'+node)
    expect(node+' untouched compiled genfs label',context,'u:object_r:proc_gpufreqv2:s0')
    for perm in ['open','read','write']:
        permission('galaxian_parts_app',context,'file',perm,False)
parent = genfs('/gpufreqv2','dir')
expect('Parent label retained',parent,'u:object_r:proc_gpufreqv2:s0')
permission('galaxian_parts_app',parent,'dir','search',True)
permission('galaxian_parts_app',parent,'dir','write',False)
permission('vendor_init','u:object_r:vendor_mtk_device_prop:s0','file','read',True)
print(f'{checks} effective GPU labels/access checks; {len(failures)} failures')
for failure in failures:
    print('FAIL '+failure)
raise SystemExit(bool(failures))
