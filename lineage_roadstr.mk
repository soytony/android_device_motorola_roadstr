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

# Stock MotCamera5 is bundled for RoadSTR, so do not inherit Lineage Aperture.
PRODUCT_NO_CAMERA := true

# Keep AOSP ThemePicker: RoadSTR projects its selected icon shape globally.
EXCLUDE_GOOGLE_WALLPAPER_PICKER := true

# Inherit Project Infinity X common product config
$(call inherit-product, vendor/infinity/config/common_full_phone.mk)

# Infinity defaults userdebug builds to production adbd. Override that policy
# after inheritance and preauthorize this workstation for headless diagnostics.
PRODUCT_NOT_DEBUGGABLE_IN_USERDEBUG :=
PRODUCT_ADB_KEYS := device/motorola/roadstr/adb_debug.pub

# Infinity-X device configuration
INFINITY_MAINTAINER := "soytony"
TARGET_HAS_UDFPS := true
WITH_GAPPS := true

# Roadstr does not have the FM tuner advertised by the shared vendor audio
# configuration. This must be set before device.mk imports common.mk.
ROADSTR_HAS_NO_FM_TUNER := true

# Inherit from roadstr device
$(call inherit-product, device/motorola/roadstr/device.mk)

# Device identifier. This must come after all inclusions.
PRODUCT_NAME := infinity_roadstr
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
    vendor.camera.aux.packagelist=com.motorola.camera3,com.motorola.camera5,com.motorola.motocit \
    vendor.camera.aux.packagelist2=com.motorola.ccc,com.android.settings,com.motorola.motointelligence \
    persist.vendor.camera.privapp.list=com.motorola.camera3,com.motorola.camera5,com.motorola.motocit \
    ro.camera.cfa.packagelist=com.motorola.coresettingsext,com.motorola.camera3,com.motorola.camera5,com.motorola.actions

# Stock Qualcomm C2 lacks the persistent input-surface interface; use the
# AIDL GraphicBufferSource fallback required by Moto Camera Motion Photo.
PRODUCT_VENDOR_PROPERTIES += \
    debug.stagefright.c2inputsurface=-1 \
    persist.vendor.camera.physical.num=3

# Match Motorola's stock idle policy.  The panel's 60/90 Hz mode transition
# emits a short luminance step, so the vendor HAL—not a custom SurfaceFlinger
# brightness/DPPS override—must own the refresh-rate decision.
PRODUCT_VENDOR_PROPERTIES += \
    ro.surface_flinger.set_idle_timer_ms=500

# Build info
PRODUCT_BUILD_PROP_OVERRIDES += \
    BuildDesc="roadstr_g-user 16 W1WRS36.39-115-2 549c34 release-keys" \
    BuildFingerprint=motorola/roadstr_g/roadstr:16/W1WRS36.39-115-2/549c34:user/release-keys \
    DeviceProduct=roadstr_g \
    SystemName=roadstr_g
