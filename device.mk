#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from sm7750-common
$(call inherit-product, device/motorola/sm7750-common/common.mk)

# Soong namespaces
PRODUCT_SOONG_NAMESPACES += \
    $(LOCAL_PATH)

# Overlays
DEVICE_PACKAGE_OVERLAYS += \
    $(LOCAL_PATH)/overlay-lineage

PRODUCT_PACKAGES += \
    FrameworksResRoadstr \
    SettingsResRoadstr \
    SystemUIResRoadstr

# Audio
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/audio/sku_sun_audio_effects.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_sun/audio_effects.xml \
    $(LOCAL_PATH)/audio/sku_sun_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_sun/audio_policy_configuration.xml \
    $(LOCAL_PATH)/audio/sku_sun_quasar_config.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_sun/quasar_config.xml \
    $(LOCAL_PATH)/audio/audio_module_config_primary.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_sun/audio_module_config_primary.xml \
    $(LOCAL_PATH)/audio/audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_sun/audio_policy_configuration.xml \
    $(LOCAL_PATH)/audio/sku_sun_audio_effects.conf:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_sun/audio_effects.conf

# Init
PRODUCT_PACKAGES += \
    init.device.rc

# LiveDisplay
$(call soong_config_set_bool,livedisplay_sysfs,enable_ab,true)

# Media
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/media_profiles_V1_0.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_profiles_V1_0.xml \
    $(LOCAL_PATH)/configs/media_profiles_sun.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_profiles_vendor.xml \
    $(LOCAL_PATH)/configs/media_codecs.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs.xml

# NFC - STMicro ST54L
PRODUCT_PACKAGES += \
    android.hardware.nfc-service.st

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/nfc/libnfc-hal-st.conf:$(TARGET_COPY_OUT_VENDOR)/etc/libnfc-hal-st.conf \
    $(LOCAL_PATH)/nfc/libnfc-hal-st-uicc.conf:$(TARGET_COPY_OUT_VENDOR)/etc/libnfc-hal-st-uicc.conf \
    $(LOCAL_PATH)/nfc/libnfc-hal-st-prc.conf:$(TARGET_COPY_OUT_VENDOR)/etc/libnfc-hal-st-prc.conf \
    $(LOCAL_PATH)/nfc/libnfc-hal-st-felica.conf:$(TARGET_COPY_OUT_VENDOR)/etc/libnfc-hal-st-felica.conf \
    $(LOCAL_PATH)/nfc/libnfc-SN220_19_2MHZ.conf:$(TARGET_COPY_OUT_VENDOR)/etc/libnfc-SN220_19_2MHZ.conf \
    $(LOCAL_PATH)/nfc/libnfc-SN220_38_4MHZ.conf:$(TARGET_COPY_OUT_VENDOR)/etc/libnfc-SN220_38_4MHZ.conf \
    $(LOCAL_PATH)/nfc/libnfc-SN300_38_4MHZ.conf:$(TARGET_COPY_OUT_VENDOR)/etc/libnfc-SN300_38_4MHZ.conf

# Get non-open-source specific aspects
$(call inherit-product-if-exists, vendor/motorola/roadstr/roadstr-vendor.mk)
