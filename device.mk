#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# Boot animation
TARGET_SCREEN_HEIGHT := 2712
TARGET_SCREEN_WIDTH := 1220

# Screen
TARGET_SCREEN_DENSITY := 460

# AAPT
PRODUCT_AAPT_CONFIG := normal
PRODUCT_AAPT_PREF_CONFIG := 460dpi
PRODUCT_AAPT_PREBUILT_DPI := xxxhdpi xxhdpi xhdpi hdpi

BOARD_SHIPPING_API_LEVEL := 202404
PRODUCT_SHIPPING_API_LEVEL := 36

# Characteristics
PRODUCT_CHARACTERISTICS := nosdcard

# Inherit from sm7750-common
$(call inherit-product, device/motorola/sm7750-common/common.mk)

# The stock touch driver reports double tap as KEY_F4 and uses the gesture
# command ABI instead of Lineage's double_tap_* sysfs attributes.
$(call soong_config_set_bool,moto_sensors,legacy_double_tap,true)
$(call soong_config_set,power_libperfmgr,mode_extension_lib,//$(LOCAL_PATH)/lineagehw/touch:libperfmgr-ext-roadstr)

# Soong namespaces
PRODUCT_SOONG_NAMESPACES += \
    $(LOCAL_PATH) \
    $(LOCAL_PATH)/overlay

# Overlays
PRODUCT_SOONG_NAMESPACES += \
    $(LOCAL_PATH)/overlay

PRODUCT_PACKAGES += \
    roadstr-udfps-hbm \
    ApertureResRoadstr \
    FrameworksResQcomCommon \
    FrameworksResRoadstr \
    SettingsAdaptiveColorsRoadstr \
    SettingsResRoadstr \
    SystemUIResRoadstr \
    NetworkStackResMcc460Roadstr \
    NetworkStackGoogleResMcc460Roadstr \
    NfcResRoadstr

# Audio
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/audio/sku_sun_audio_effects.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_sun/audio_effects.xml \
    $(LOCAL_PATH)/audio/sku_sun_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_sun/audio_policy_configuration.xml \
    $(LOCAL_PATH)/audio/sku_sun_quasar_config.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_sun/quasar_config.xml \
    $(LOCAL_PATH)/audio/audio_module_config_primary.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_sun/audio_module_config_primary.xml \
    $(LOCAL_PATH)/audio/sku_sun_audio_effects.conf:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_sun/audio_effects.conf

# LiveDisplay
$(call soong_config_set_bool,livedisplay_sysfs,enable_ab,true)

# NFC - STMicro ST54L
PRODUCT_PACKAGES += \
    android.hardware.nfc-service.st

# Media
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/media_profiles_V1_0.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_profiles_V1_0.xml \
    $(LOCAL_PATH)/configs/media_profiles_sun.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_profiles_vendor.xml \
    $(LOCAL_PATH)/configs/media_codecs.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs.xml

# Get non-open-source specific aspects
$(call inherit-product-if-exists, vendor/motorola/roadstr/roadstr-vendor.mk)
