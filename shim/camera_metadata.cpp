/*
 * Copyright (C) 2022 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include <dlfcn.h>
#include <system/camera_metadata.h>
#include <vector>

// Helper to check if a metadata buffer contains a telephoto focal length (> 7.0)
bool is_telephoto(const camera_metadata_t* dst, const float* data, size_t data_count) {
    // Check current data being added/updated
    if (data != nullptr) {
        for (size_t i = 0; i < data_count; i++) {
            if (data[i] > 7.0f) return true;
        }
    }
    // Check existing buffer
    camera_metadata_entry_t entry;
    if (find_camera_metadata_entry(const_cast<camera_metadata_t*>(dst), 
                                   ANDROID_LENS_INFO_AVAILABLE_FOCAL_LENGTHS, &entry) == 0) {
        for (size_t i = 0; i < entry.count; i++) {
            if (entry.data.f[i] > 7.0f) return true;
        }
    }
    return false;
}

extern "C" int add_camera_metadata_entry(camera_metadata_t* dst, uint32_t tag, const void* data,
                                         size_t data_count) {
    static auto add_orig = reinterpret_cast<typeof(add_camera_metadata_entry)*>(
            dlsym(RTLD_NEXT, "add_camera_metadata_entry"));
    static auto update_orig = reinterpret_cast<typeof(update_camera_metadata_entry)*>(
            dlsym(RTLD_NEXT, "update_camera_metadata_entry"));

    // 1. Remove System Camera Capability
    if (tag == ANDROID_REQUEST_AVAILABLE_CAPABILITIES) {
        std::vector<uint8_t> caps;
        auto u8 = reinterpret_cast<const uint8_t*>(data);
        for (size_t i = 0; i < data_count; i++) {
            if (u8[i] != ANDROID_REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA) {
                caps.emplace_back(u8[i]);
            }
        }
        return add_orig(dst, tag, caps.data(), caps.size());
    }

    // 2. Force Telephoto to BACK facing
    if (tag == ANDROID_LENS_FACING) {
        if (is_telephoto(dst, nullptr, 0)) {
            uint8_t facing = ANDROID_LENS_FACING_BACK;
            return add_orig(dst, tag, &facing, 1);
        }
    }

    // 3. If Focal Lengths are added later, back-patch the Facing entry
    if (tag == ANDROID_LENS_INFO_AVAILABLE_FOCAL_LENGTHS) {
        if (is_telephoto(dst, reinterpret_cast<const float*>(data), data_count)) {
            camera_metadata_entry_t entry;
            if (find_camera_metadata_entry(dst, ANDROID_LENS_FACING, &entry) == 0) {
                uint8_t facing = ANDROID_LENS_FACING_BACK;
                update_orig(dst, entry.index, &facing, 1, nullptr);
            }
        }
    }

    return add_orig(dst, tag, data, data_count);
}

extern "C" int update_camera_metadata_entry(camera_metadata_t* dst, size_t index, const void* data,
                                            size_t data_count,
                                            camera_metadata_entry_t* updated_entry) {
    static auto update_orig = reinterpret_cast<typeof(update_camera_metadata_entry)*>(
            dlsym(RTLD_NEXT, "update_camera_metadata_entry"));

    camera_metadata_entry_t entry;
    if (get_camera_metadata_entry(dst, index, &entry) != 0) {
        return update_orig(dst, index, data, data_count, updated_entry);
    }

    // Filter System Camera Capability on Update
    if (entry.tag == ANDROID_REQUEST_AVAILABLE_CAPABILITIES) {
        std::vector<uint8_t> caps;
        auto u8 = reinterpret_cast<const uint8_t*>(data);
        for (size_t i = 0; i < data_count; i++) {
            if (u8[i] != ANDROID_REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA) {
                caps.emplace_back(u8[i]);
            }
        }
        return update_orig(dst, index, caps.data(), caps.size(), updated_entry);
    }

    // Prevent HAL from overwriting BACK with FRONT on Telephoto
    if (entry.tag == ANDROID_LENS_FACING) {
        if (is_telephoto(dst, nullptr, 0)) {
            uint8_t facing = ANDROID_LENS_FACING_BACK;
            return update_orig(dst, index, &facing, 1, updated_entry);
        }
    }

    return update_orig(dst, index, data, data_count, updated_entry);
}
