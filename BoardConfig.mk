#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

include device/motorola/sm7750-common/BoardConfigCommon.mk

DEVICE_PATH := device/motorola/roadstr

# Bootloader
TARGET_BOOTLOADER_BOARD_NAME := roadstr

# Display
TARGET_SCREEN_DENSITY := 480

# Kernel
TARGET_PREBUILT_KERNEL := $(DEVICE_PATH)/prebuilt/kernel
BOARD_PREBUILT_DTBIMAGE_DIR := $(DEVICE_PATH)/prebuilt/dtb
BOARD_PREBUILT_DTBOIMAGE := $(DEVICE_PATH)/prebuilt/dtbo.img

# Partitions
BOARD_SUPER_PARTITION_SIZE := 21474836480

# Security patch level
VENDOR_SECURITY_PATCH := 2025-11-01

# SEPolicy
BOARD_VENDOR_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/vendor

# Inherit from the proprietary version
-include vendor/motorola/roadstr/BoardConfigVendor.mk
