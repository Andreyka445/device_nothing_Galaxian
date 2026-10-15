#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check the shipped policy's existing compositor access and exact read-only GPU counter."""
import argparse
import ctypes as C
from pathlib import Path
import sys
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--policy',type=Path,required=True)
parser.add_argument('--libsepol',type=Path,required=True)
args=parser.parse_args()
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'thermal/tests'))
from check_property_access import OfflinePolicy
policy=OfflinePolicy(args.policy,args.libsepol)
lib=policy.lib
lib.sepol_genfs_sid.argtypes=[C.c_char_p,C.c_char_p,C.c_uint16,C.POINTER(C.c_uint32)]
lib.sepol_sid_to_context.argtypes=[C.c_uint32,C.POINTER(C.c_char_p),C.POINTER(C.c_size_t)]
libc=C.CDLL(None);libc.free.argtypes=[C.c_void_p]
checks=0;failures=[]
def expect(label,actual,expected):
 global checks
 checks+=1
 if actual!=expected:failures.append(f'{label}: got {actual}, expected {expected}')
def context(path,klass='file'):
 cls=C.c_uint16();sid=C.c_uint32();result=C.c_char_p();length=C.c_size_t()
 assert lib.sepol_string_to_security_class(klass.encode(),C.byref(cls))==0
 assert lib.sepol_genfs_sid(b'sysfs',path.encode(),cls,C.byref(sid))==0
 assert lib.sepol_sid_to_context(sid,C.byref(result),C.byref(length))==0
 try:return result.value.decode()
 finally:libc.free(result)
def check(domain,target,klass,perm,expected):
 actual,_=policy.allowed('u:r:'+domain+':s0',target,klass,perm)
 expect(f'{domain} -> {target} {klass}:{perm}',actual,expected)
node=context('/kernel/ged/hal/gpu_utilization')
expect('Exact GPU counter label',node,'u:object_r:vendor_galaxian_gpu_utilization:s0')
for perm in ['getattr','open','read']:check('galaxian_parts_app',node,'file',perm,True)
for perm in ['write','append','setattr','unlink','create','rename']:check('galaxian_parts_app',node,'file',perm,False)
for path in ['/kernel/ged','/kernel/ged/hal']:
 parent=context(path,'dir');expect('Existing parent label',parent,'u:object_r:sysfs_ged:s0')
 for perm,wanted in [('search',True),('write',False),('add_name',False),('remove_name',False)]:
  check('galaxian_parts_app',parent,'dir',perm,wanted)
for path in ['gpu_boost_level','custom_boost_gpu_freq','custom_upbound_gpu_freq','current_freqency']:
 sibling=context('/kernel/ged/hal/'+path)
 expect('Untouched GED sibling label',sibling,'u:object_r:sysfs_ged:s0')
 for perm in ['open','read','write']:check('galaxian_parts_app',sibling,'file',perm,False)
for domain in ['hal_power_default','mtk_hal_power']:
 for perm in ['getattr','open','read','write','append','lock','map','ioctl']:check(domain,node,'file',perm,True)
# Galaxian ships no magt daemon and defines no such type, so any magt
# query must fail context resolution: no access is possible at all.
try:
    policy.allowed('u:r:magt:s0',node,'file','read')
    expect('magt domain absent',True,False)
except RuntimeError:
    expect('magt domain absent',True,True)
for domain in ['untrusted_app','system_app']:
 for perm in ['read','write']:check(domain,node,'file',perm,False)
for perm in ['create','read','write','getopt','setopt','shutdown','connectto']:
 check('galaxian_parts_app','u:r:galaxian_parts_app:s0','unix_stream_socket',perm,True)
check('galaxian_parts_app','u:object_r:surfaceflinger_service:s0','service_manager','find',True)
check('galaxian_parts_app','u:object_r:surfaceflinger_service:s0','service_manager','add',False)
for perm in ['call','transfer']:check('galaxian_parts_app','u:r:surfaceflinger:s0','binder',perm,True)
check('surfaceflinger','u:r:galaxian_parts_app:s0','fd','use',True)
for perm in ['read','write']:check('surfaceflinger','u:r:galaxian_parts_app:s0','unix_stream_socket',perm,True)
print(f'{checks} effective frame/GPU telemetry policy checks; {len(failures)} failures')
for error in failures:print('FAIL '+error)
raise SystemExit(bool(failures))
