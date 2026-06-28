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
# Prebuilt kernel will be configured in Phase 2
# TARGET_KERNEL_CONFIG += vendor/ext_config/roadstr-default.config

# Partitions
BOARD_SUPER_PARTITION_SIZE := 21474836480

# Security patch level
VENDOR_SECURITY_PATCH := 2025-11-01

# SEPolicy
BOARD_VENDOR_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/vendor

# Inherit from the proprietary version
-include vendor/motorola/roadstr/BoardConfigVendor.mk
