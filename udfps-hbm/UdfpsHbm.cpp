/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "RoadstrUdfpsHbm"

#include <android-base/unique_fd.h>
#include <android/binder_auto_utils.h>
#include <android/binder_ibinder.h>
#include <android/binder_manager.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>
#include <android/log.h>
#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <limits.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <cstring>
#include <utility>

namespace {

constexpr char kPanelService[] =
        "com.motorola.hardware.display.panel.IDisplayPanel/default";
constexpr char kPanelDescriptor[] =
        "com.motorola.hardware.display.panel.IDisplayPanel";
constexpr char kTouchDeviceName[] = "goodix_ts";

// Stable NDK AIDL transaction number for IDisplayPanel.setMode(PanelMode).
constexpr transaction_code_t kSetModeTransaction = FIRST_CALL_TRANSACTION + 4;
constexpr uint32_t kPrivateVendorFlag = 0x10000000;

// Values from Motorola's PanelMode enum, confirmed against the running stock service.
constexpr int32_t kPanelModeNormal = 0;
constexpr int32_t kPanelModeHighBrightFod = 4;

// The stock keylayout documents these Goodix events as FOD finger down and finger up.
constexpr uint16_t kFingerDownCode = BTN_TRIGGER_HAPPY;
constexpr uint16_t kFingerUpCode = BTN_TRIGGER_HAPPY2;

ndk::SpAIBinder gPanel;

void* onBinderCreate(void* args) {
    return args;
}

void onBinderDestroy(void*) {}

binder_status_t onBinderTransact(AIBinder*, transaction_code_t, const AParcel*, AParcel*) {
    return STATUS_UNKNOWN_TRANSACTION;
}

const AIBinder_Class* getPanelClass() {
    static const AIBinder_Class* clazz =
            AIBinder_Class_define(kPanelDescriptor, onBinderCreate, onBinderDestroy,
                                  onBinderTransact);
    return clazz;
}

bool setPanelMode(int32_t mode) {
    if (!gPanel.get()) {
        gPanel = ndk::SpAIBinder(AServiceManager_waitForService(kPanelService));
        if (!gPanel.get()) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Panel service is unavailable");
            return false;
        }
        if (!AIBinder_associateClass(gPanel.get(), getPanelClass())) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                                "Panel service has an unexpected interface");
            gPanel = nullptr;
            return false;
        }
    }

    AParcel* rawIn = nullptr;
    binder_status_t status = AIBinder_prepareTransaction(gPanel.get(), &rawIn);
    ndk::ScopedAParcel in(rawIn);
    if (status == STATUS_OK) {
        status = AParcel_writeInt32(in.get(), mode);
    }

    AParcel* rawOut = nullptr;
    if (status == STATUS_OK) {
        rawIn = in.release();
        status = AIBinder_transact(gPanel.get(), kSetModeTransaction, &rawIn, &rawOut,
                                   kPrivateVendorFlag);
    }
    ndk::ScopedAParcel out(rawOut);

    if (status != STATUS_OK) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "Failed to set panel mode %d: status=%d", mode, status);
        gPanel = nullptr;
        return false;
    }

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Set panel mode %d", mode);
    return true;
}

android::base::unique_fd openTouchDevice() {
    DIR* inputDir = opendir("/dev/input");
    if (inputDir == nullptr) {
        return {};
    }

    android::base::unique_fd touchFd;
    while (dirent* entry = readdir(inputDir)) {
        if (strncmp(entry->d_name, "event", 5) != 0) {
            continue;
        }

        char path[PATH_MAX];
        snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);
        android::base::unique_fd candidate(open(path, O_RDONLY | O_CLOEXEC));
        if (candidate.get() < 0) {
            continue;
        }

        char name[128] = {};
        if (ioctl(candidate.get(), EVIOCGNAME(sizeof(name)), name) >= 0 &&
            strcmp(name, kTouchDeviceName) == 0) {
            touchFd = std::move(candidate);
            break;
        }
    }

    closedir(inputDir);
    return touchFd;
}

}  // namespace

int main() {
    // Recover from a daemon restart while a previous HBM request was active.
    setPanelMode(kPanelModeNormal);

    for (;;) {
        android::base::unique_fd touchFd = openTouchDevice();
        if (touchFd.get() < 0) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Goodix input device is unavailable");
            sleep(1);
            continue;
        }

        pollfd pfd = {.fd = touchFd.get(), .events = POLLIN, .revents = 0};
        while (poll(&pfd, 1, -1) > 0) {
            input_event event;
            if (read(touchFd.get(), &event, sizeof(event)) != sizeof(event)) {
                break;
            }

            // Only act on the key-down edge; each dedicated FOD code also emits a key-up edge.
            if (event.type != EV_KEY || event.value != 1) {
                continue;
            }
            if (event.code == kFingerDownCode) {
                setPanelMode(kPanelModeHighBrightFod);
            } else if (event.code == kFingerUpCode) {
                setPanelMode(kPanelModeNormal);
            }
        }
    }
}
