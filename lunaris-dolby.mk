#
# SPDX-License-Identifier: Apache-2.0
#
# Roadstr integration for vendor/lunaris/dolby. Roadstr already provides its
# own compatible Motorola Dolby HAL through sm7750-common and roadstr-vendor;
# only the Lunaris control surface and its resource/permission metadata are
# selected here.

LUNARIS_DOLBY_PATH := vendor/lunaris/dolby

PRODUCT_SOONG_NAMESPACES += \
    $(LUNARIS_DOLBY_PATH)

PRODUCT_PACKAGES += \
    LunarisDolby \
    XiaomiDolbyResCommon \
    init.dolby.rc \
    LunarisDolbyPrivappPermissions
