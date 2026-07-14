/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include <aidl/android/hardware/power/BnPower.h>
#include <android-base/file.h>

namespace aidl::google::hardware::power::impl::pixel {

using ::aidl::android::hardware::power::Mode;

namespace {

constexpr auto kGestureNode = "/sys/class/touchscreen/primary/gesture";
constexpr auto kDoubleTapDisable = "48";  // Motorola command 0x30.
constexpr auto kDoubleTapEnable = "49";   // Motorola command 0x31.

}  // namespace

bool isDeviceSpecificModeSupported(Mode type, bool* supported) {
    if (type != Mode::DOUBLE_TAP_TO_WAKE) return false;

    *supported = true;
    return true;
}

bool setDeviceSpecificMode(Mode type, bool enabled) {
    if (type != Mode::DOUBLE_TAP_TO_WAKE) return false;

    return ::android::base::WriteStringToFile(enabled ? kDoubleTapEnable : kDoubleTapDisable,
                                              kGestureNode);
}

}  // namespace aidl::google::hardware::power::impl::pixel
