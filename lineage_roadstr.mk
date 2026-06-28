#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# Inherit AOSP 64-bit-only product config (sets TARGET_SUPPORTS_64_BIT_APPS)
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)

# Inherit LineageOS common product config
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

# Inherit from roadstr device
$(call inherit-product, device/motorola/roadstr/device.mk)

# Device identifier. This must come after all inclusions.
PRODUCT_NAME := lineage_roadstr
PRODUCT_DEVICE := roadstr
PRODUCT_BRAND := motorola
PRODUCT_MODEL := Edge 70
PRODUCT_MANUFACTURER := motorola

# Build info
PRODUCT_BUILD_PROP_OVERRIDES += \
    BuildDesc="roadstr_g-user 16 W1WRS36.39-25-2-1 aa3747-f1b0b release-keys" \
    BuildFingerprint=motorola/roadstr_g/roadstr:16/W1WRS36.39-25-2-1/aa3747-f1b0b:user/release-keys \
    DeviceProduct=roadstr_g \
    SystemName=roadstr_g
