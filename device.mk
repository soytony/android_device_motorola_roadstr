#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# Boot animation
TARGET_SCREEN_HEIGHT := 2712
TARGET_SCREEN_WIDTH := 1220

# Screen
TARGET_SCREEN_DENSITY := 501

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
    moto-framework-roadstr \
    moto-framework-roadstr-permissions \
    roadstr-udfps-hbm \
    ReFra \
    ApertureResRoadstr \
    FrameworksResQcomCommon \
    FrameworksResRoadstr \
    LineageSdkRoadstr \
    LineagePartsActionButtonRoadstr \
    SystemGalleryOverlayRoadstr \
    SettingsAdaptiveColorsRoadstr \
    SettingsResRoadstr \
    SystemUIResRoadstr \
    NetworkLocation460 \
    NetworkStackResMcc460Roadstr \
    NetworkStackGoogleResMcc460Roadstr \
    NfcResRoadstr

# The kernel exposes the dedicated left-side GPIO button as KEY_SEARCH (217).
# Map this input device to Android's assistant key so input policy owns its
# wake, keyguard, single-press and multi-press behavior.
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/keylayout/gpio-keys.kl:$(TARGET_COPY_OUT_SYSTEM)/usr/keylayout/gpio-keys.kl

# Face unlock
# Stock provides an AIDL v4 RGB face HAL; advertise it so Settings and
# BiometricService expose face enrollment and authentication.
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/permissions/default-permissions-refra.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/default-permissions/default-permissions-refra.xml \
    frameworks/native/data/etc/android.hardware.biometrics.face.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.biometrics.face.xml

# Audio
# The Qualcomm AIDL effect factory only probes /vendor/etc/audio_effects_config.xml.
# Motorola stores the functional configuration under the selected SKU, so publish
# that configuration at the factory's standard path and install its AIDL effects.
PRODUCT_PACKAGES += \
    libbundleaidl \
    libdownmixaidl \
    libdynamicsprocessingaidl \
    libloudnessenhanceraidl \
    libreverbaidl \
    libvisualizeraidl

PRODUCT_COPY_FILES += \
    vendor/motorola/sm7750-common/proprietary/vendor/etc/audio/sku_sun/audio_effects_config.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_effects_config.xml \
    $(LOCAL_PATH)/audio/sku_sun_audio_effects.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_sun/audio_effects.xml \
    $(LOCAL_PATH)/audio/sku_sun_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_sun/audio_policy_configuration.xml \
    $(LOCAL_PATH)/audio/sku_sun_quasar_config.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_sun/quasar_config.xml \
    $(LOCAL_PATH)/audio/audio_module_config_primary.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/audio_module_config_primary.xml \
    $(LOCAL_PATH)/audio/audio_module_config_primary.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_sun/audio_module_config_primary.xml \
    $(LOCAL_PATH)/audio/sku_sun_audio_effects.conf:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_sun/audio_effects.conf

# Display
# The current composer reports this display ID instead of the IDs encoded in
# stock. Install the byte-identical stock profile under the reported ID so the
# framework loads the panel's HBM and HDR brightness configuration.
PRODUCT_COPY_FILES += \
    vendor/motorola/sm7750-common/proprietary/vendor/etc/displayconfig/display_id_4630947039571902850.xml:$(TARGET_COPY_OUT_VENDOR)/etc/displayconfig/display_id_4630946747577212050.xml

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
