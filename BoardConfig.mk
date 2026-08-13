#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

include device/motorola/sm7750-common/BoardConfigCommon.mk

DEVICE_PATH := device/motorola/roadstr

# Locally supplied release signing settings are deliberately optional so that
# public device-tree builds continue to use the standard test keys.
-include $(DEVICE_PATH)/BoardConfigPrivate.mk

# Bootloader
TARGET_BOOTLOADER_BOARD_NAME := roadstr

# Display
# 1220 px * 160 / 501 dpi = 389.6 dp minimum width.
TARGET_SCREEN_DENSITY := 501

# Kernel
TARGET_KERNEL_VERSION := 6.6
TARGET_PREBUILT_KERNEL := $(DEVICE_PATH)/prebuilt/kernel
TARGET_PREBUILT_KERNEL_HEADERS := $(DEVICE_PATH)/prebuilt/kernel-headers.tar.gz
BOARD_PREBUILT_DTBIMAGE_DIR := $(DEVICE_PATH)/prebuilt/dtb
BOARD_PREBUILT_DTBOIMAGE := $(DEVICE_PATH)/prebuilt/dtbo.img

# Partitions
BOARD_SUPER_PARTITION_SIZE := 21474836480

# MindTheGapps patches the logical system partitions from recovery. Keep all
# partitions it touches writable, and reserve enough free ext4 space for the
# package and future updates. These RoadSTR-specific overrides deliberately do
# not change the filesystem policy for other sm7750-common devices.
BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_PRODUCTIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_SYSTEM_EXTIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := ext4

BOARD_SYSTEMIMAGE_PARTITION_RESERVED_SIZE := 536870912
# Keep 2 GiB free in product for recovery-installed GApps and future updates.
BOARD_PRODUCTIMAGE_PARTITION_RESERVED_SIZE := 2147483648
BOARD_SYSTEM_EXTIMAGE_PARTITION_RESERVED_SIZE := 536870912
BOARD_VENDORIMAGE_PARTITION_RESERVED_SIZE := 268435456

# Security patch level
VENDOR_SECURITY_PATCH := 2025-11-01

# RoadSTR's bootloader has a root vbmeta rollback floor of 1. Leaving this unset emits zero,
# which is rejected. Keep the known device floor instead of permanently advancing it.
BOARD_AVB_ROLLBACK_INDEX := 1

# SEPolicy
BOARD_VENDOR_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/vendor
SYSTEM_EXT_PUBLIC_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/system_ext/public
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/system_ext/private
BOARD_SYSTEM_EXT_SEPOLICY_PREBUILT_DIRS += $(DEVICE_PATH)/sepolicy/system_ext

# Inherit from the proprietary version
-include vendor/motorola/roadstr/BoardConfigVendor.mk
