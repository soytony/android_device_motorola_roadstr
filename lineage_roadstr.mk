#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# Inherit AOSP 64-bit-only product config (sets TARGET_SUPPORTS_64_BIT_APPS)
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)

# Inherit AOSP full phone product config
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Keep normal-system ADB authorization-free while boot debugging.
WITH_ADB_INSECURE := true

# Inherit LineageOS common product config
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

# Roadstr does not have the FM tuner advertised by the shared vendor audio
# configuration. This must be set before device.mk imports common.mk.
ROADSTR_HAS_NO_FM_TUNER := true

# Inherit from roadstr device
$(call inherit-product, device/motorola/roadstr/device.mk)

# Device identifier. This must come after all inclusions.
PRODUCT_NAME := lineage_roadstr
PRODUCT_DEVICE := roadstr
PRODUCT_BRAND := motorola
PRODUCT_MODEL := Edge 70
PRODUCT_MANUFACTURER := motorola

# Keep telephony and CamX policy in sync with stock.  The radio framework uses
# this property before carrier configuration is loaded; without it it falls
# back to the legacy 3G mode.  CamX hides auxiliary cameras from packages not
# present in these allowlists.
PRODUCT_PRODUCT_PROPERTIES += \
    ro.telephony.default_network=26,26

PRODUCT_SYSTEM_EXT_PROPERTIES += \
    persist.vendor.camera.expose.aux=1 \
    vendor.camera.aux.packagelist=com.motorola.camera3,com.motorola.camera5,com.motorola.motocit,org.lineageos.aperture \
    vendor.camera.aux.packagelist2=com.motorola.ccc,com.android.settings,com.motorola.motointelligence \
    persist.vendor.camera.privapp.list=com.motorola.camera3,com.motorola.camera5,com.motorola.motocit,org.lineageos.aperture \
    ro.camera.cfa.packagelist=com.motorola.coresettingsext,com.motorola.camera3,com.motorola.camera5,com.motorola.actions

# Stock Qualcomm C2 lacks the persistent input-surface interface; use the
# AIDL GraphicBufferSource fallback required by Moto Camera Motion Photo.
PRODUCT_VENDOR_PROPERTIES += \
    debug.stagefright.c2inputsurface=-1 \
    persist.vendor.camera.physical.num=3 \
    ro.surface_flinger.touch_boost_across_groups=true

# Keep the Visionox OLED's brightness and 60/90/120 Hz mode commits in sync.
# The DRM connector brightness property makes the Composer include pending
# brightness in the matching atomic commit, while DPPS receives the panel's
# current FPS to update its dimming policy across refresh-rate transitions.
PRODUCT_VENDOR_PROPERTIES += \
    ro.surface_flinger.set_idle_timer_ms=2000 \
    vendor.display.enable_brightness_drm_prop=1 \
    vendor.display.enable_dpps_dynamic_fps=1

# Build info
PRODUCT_BUILD_PROP_OVERRIDES += \
    BuildDesc="roadstr_g-user 16 W1WRS36.39-25-2-1 aa3747-f1b0b release-keys" \
    BuildFingerprint=motorola/roadstr_g/roadstr:16/W1WRS36.39-25-2-1/aa3747-f1b0b:user/release-keys \
    DeviceProduct=roadstr_g \
    SystemName=roadstr_g
