#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

from extract_utils.fixups_blob import (
    blob_fixup,
    blob_fixups_user_type,
)
from extract_utils.fixups_lib import (
    lib_fixups,
    lib_fixups_user_type,
)
from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)

namespace_imports = [
    'device/nothing/Galaxian',
    'hardware/mediatek',
]

def lib_fixup_vendor_suffix(lib: str, partition: str, *args, **kwargs):
    return f'{lib}_{partition}' if partition == 'vendor' else None


lib_fixups: lib_fixups_user_type = {
    **lib_fixups,
    'vendor.mediatek.hardware.videotelephony-v1-ndk': lib_fixup_vendor_suffix,
}

blob_fixups: blob_fixups_user_type = {
    (
        'system_ext/etc/init/init.vtservice.rc',
        'vendor/etc/init/android.hardware.neuralnetworks-shim-service-mtk.rc'
    ): blob_fixup()
        .regex_replace('start', 'enable'),
    'system_ext/priv-app/ImsService/ImsService.apk': blob_fixup()
        .apktool_patch('ims-patches'),
    'system_ext/lib64/libimsma.so': blob_fixup()
        .replace_needed('libsink.so', 'libsink-mtk.so'),
    'vendor/bin/hw/mt6878/camerahalserver': blob_fixup()
        .add_needed('libcamera_metadata_ntshim.so'),
    'vendor/lib64/hw/mt6878/vendor.mediatek.hardware.pq_aidl-impl.so': blob_fixup()
        .add_needed('libui_shim.so')
        .replace_needed('libtinyxml2.so', 'libtinyxml2-v34.so'),
    ('vendor/lib64/mt6878/lib3a.ae.stat.so', 'vendor/lib64/libarmnn_ndk.mtk.vndk.so'): blob_fixup()
        .add_needed('liblog.so'),
    ( 
        'vendor/lib64/hw/hwcomposer.mtk_common.so',
        'vendor/lib64/mt6878/libcam.hal3a.ctrl.so',
        'vendor/lib64/mt6878/libcam.hal3a.so',
        'vendor/lib64/mt6878/libmtkcam_taskmgr.so',
        'vendor/lib64/libmtkcam_cputrack.so'
    ): blob_fixup()
        .add_needed('libprocessgroup_shim.so'),
    'vendor/lib64/hw/audio.primary.mediatek.so': blob_fixup()
        .replace_needed('libalsautils.so', 'libalsautils-stock.so')
        .replace_needed('libtinyxml2.so', 'libtinyxml2-v34.so')
        .binary_regex_replace(b'A2dpsuspendonly', b'A2dpSuspended\x00\x00')
        .binary_regex_replace(b'BTAudiosuspend', b'A2dpSuspended\x00'),
    'vendor/lib64/vendor.mediatek.hardware.bluetooth.audio-V1-ndk.so': blob_fixup()
        .replace_needed('android.hardware.audio.common-V1-ndk.so', 'android.hardware.audio.common-V2-ndk.so'),
    'vendor/lib64/mt6878/libpqconfig.so': blob_fixup()
        .replace_needed('android.hardware.sensors-V2-ndk.so', 'android.hardware.sensors-V3-ndk.so'),
    'vendor/lib64/libwa_widelens_undistort_impl.so': blob_fixup()
        .clear_symbol_version('AHardwareBuffer_allocate')
        .clear_symbol_version('AHardwareBuffer_createFromHandle')
        .clear_symbol_version('AHardwareBuffer_describe')
        .clear_symbol_version('AHardwareBuffer_getNativeHandle')
        .clear_symbol_version('AHardwareBuffer_lock')
        .clear_symbol_version('AHardwareBuffer_release')
        .clear_symbol_version('AHardwareBuffer_unlock'),
    'vendor/lib64/mt6878/libneuralnetworks_sl_driver_mtk_prebuilt.so': blob_fixup()
        .clear_symbol_version('AHardwareBuffer_allocate')
        .clear_symbol_version('AHardwareBuffer_createFromHandle')
        .clear_symbol_version('AHardwareBuffer_describe')
        .clear_symbol_version('AHardwareBuffer_getNativeHandle')
        .clear_symbol_version('AHardwareBuffer_lock')
        .clear_symbol_version('AHardwareBuffer_release')
        .clear_symbol_version('AHardwareBuffer_unlock')
        .add_needed('libbase_shim.so'),
    'vendor/lib64/mt6878/libneuron_adapter_mc.so': blob_fixup()
        .clear_symbol_version('AHardwareBuffer_describe'),
    (
        'vendor/lib64/libnvram.so',
        'vendor/lib64/libtflite_mtk.so'
    ): blob_fixup()
        .add_needed('libbase_shim.so'),
    'vendor/lib64/mt6878/libmtkcam_hal_aidl_common.so': blob_fixup()
        .replace_needed('android.hardware.camera.common-V2-ndk.so', 'android.hardware.camera.common-V1-ndk.so'),
    (
        'vendor/bin/hw/mt6878/android.hardware.graphics.allocator-V2-service-mediatek.mt6878',
        'vendor/lib64/egl/mt6878/libGLES_mali.so',
        'vendor/lib64/hw/mt6878/android.hardware.graphics.allocator-V2-mediatek.so',
        'vendor/lib64/hw/mt6878/android.hardware.graphics.mapper@4.0-impl-mediatek.so',
        'vendor/lib64/hw/mt6878/mapper.mediatek.so',
        'vendor/lib64/libcodec2_fsr.so',
        'vendor/lib64/libcodec2_vpp_AIMEMC_plugin.so',
        'vendor/lib64/libcodec2_vpp_AISR_plugin.so',
        'vendor/lib64/vendor.mediatek.hardware.camera.isphal-V1-ndk.so',
        'vendor/lib64/vendor.mediatek.hardware.pq_aidl-V2-ndk.so',
        'vendor/lib64/vendor.mediatek.hardware.pq_aidl-V4-ndk.so',
        'vendor/lib64/vendor.mediatek.hardware.pq_aidl-V7-ndk.so'
    ): blob_fixup()
        .replace_needed('android.hardware.graphics.common-V4-ndk.so', 'android.hardware.graphics.common-V7-ndk.so'),
    (
        'vendor/lib64/mt6878/libmtkcam_grallocutils.so',
        'vendor/lib64/libmtkcam_grallocutils_aidlv1helper.so',
    ): blob_fixup()
        .replace_needed('android.hardware.graphics.common-V4-ndk.so', 'android.hardware.graphics.common-V7-ndk.so')
        .replace_needed('android.hardware.graphics.allocator-V1-ndk.so', 'android.hardware.graphics.allocator-V2-ndk.so'),
    (
        'vendor/lib64/mt6878/libmmlpqImpl.so',
        'vendor/lib64/libpqxmlflagparser.so',
        'vendor/lib64/libpqxmlparser.so',
        'vendor/lib64/libsilkybrightnesscore.so',
    ): blob_fixup()
        .replace_needed('libtinyxml2.so', 'libtinyxml2-v34.so'),
    'vendor/bin/mnld': blob_fixup()
        .replace_needed('libmnl.so', 'libmnl-v33.so'),
}  # fmt: skip

module = ExtractUtilsModule(
    'Galaxian',
    'nothing',
    blob_fixups=blob_fixups,
    lib_fixups=lib_fixups,
    namespace_imports=namespace_imports,
    add_firmware_proprietary_file=True,
)

if __name__ == '__main__':
    utils = ExtractUtils.device(module)
    utils.run()
