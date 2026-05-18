#
# SPDX-FileCopyrightText: LineageOS
#
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from those products. Most specific first.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Inherit from device makefile.
$(call inherit-product, device/nothing/Galaxian/device.mk)

# Inherit some common lineageOS stuff.
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

TARGET_BOOT_ANIMATION_RES := 1080

PRODUCT_NAME := lineage_Galaxian
PRODUCT_DEVICE := Galaxian
PRODUCT_MANUFACTURER := Nothing
PRODUCT_BRAND := Nothing
PRODUCT_MODEL := A001T

PRODUCT_GMS_CLIENTID_BASE := android-nothing

PRODUCT_BUILD_PROP_OVERRIDES += \
    DeviceName=Galaxian \
    BuildDesc="sys_mssi_64_64only_ww_armv82-user 15 AP3A.240905.015.A2 2510291642 release-keys" \
    BuildFingerprint=alps/sys_mssi_64_64only_ww_armv82/mssi_64_64only_ww_armv82:15/AP3A.240905.015.A2/2510291642:user/release-keys