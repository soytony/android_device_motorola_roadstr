/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "RoadstrUdfpsHbm"

#include <aidl/vendor/lineage/biometrics/udfps/BnUdfpsHbm.h>
#include <android/binder_auto_utils.h>
#include <android/binder_ibinder.h>
#include <android/binder_manager.h>
#include <android/binder_parcel.h>
#include <android/binder_process.h>
#include <android/binder_status.h>
#include <android/log.h>

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>

namespace {

using aidl::vendor::lineage::biometrics::udfps::BnUdfpsHbm;

constexpr char kServiceName[] =
        "vendor.lineage.biometrics.udfps.IUdfpsHbm/default";
constexpr char kPanelService[] =
        "com.motorola.hardware.display.panel.IDisplayPanel/default";
constexpr char kPanelDescriptor[] =
        "com.motorola.hardware.display.panel.IDisplayPanel";

// Stable NDK AIDL transaction number for IDisplayPanel.setMode(PanelMode).
constexpr transaction_code_t kSetModeTransaction = FIRST_CALL_TRANSACTION + 4;
constexpr uint32_t kPrivateVendorFlag = 0x10000000;

// Values from Motorola's PanelMode enum, confirmed against the running stock service.
constexpr int32_t kPanelModeNormal = 0;
constexpr int32_t kPanelModeHighBrightFod = 4;
constexpr auto kHbmWatchdogTimeout = std::chrono::seconds(10);

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

class UdfpsHbm : public BnUdfpsHbm {
  public:
    UdfpsHbm() : mWatchdog(&UdfpsHbm::watchdogLoop, this) {}

    ~UdfpsHbm() override {
        {
            std::lock_guard lock(mMutex);
            mStopping = true;
            mEnabled = false;
            ++mGeneration;
        }
        mCondition.notify_all();
        mWatchdog.join();
        setPanelMode(kPanelModeNormal);
    }

    ndk::ScopedAStatus setEnabled(bool enabled, bool* result) override {
        std::lock_guard lock(mMutex);

        if (enabled == mEnabled && enabled) {
            *result = true;
            return ndk::ScopedAStatus::ok();
        }

        // Always send disable so stale panel state is repaired after a client failure.
        const bool success = setPanelMode(enabled ? kPanelModeHighBrightFod : kPanelModeNormal);
        if (success) {
            mEnabled = enabled;
            ++mGeneration;
            mCondition.notify_all();
        }
        *result = success;
        return ndk::ScopedAStatus::ok();
    }

  private:
    void watchdogLoop() {
        std::unique_lock lock(mMutex);
        while (!mStopping) {
            mCondition.wait(lock, [this] { return mStopping || mEnabled; });
            if (mStopping) {
                break;
            }

            const uint64_t generation = mGeneration;
            const bool changed = mCondition.wait_for(lock, kHbmWatchdogTimeout, [this, generation] {
                return mStopping || !mEnabled || mGeneration != generation;
            });
            if (!changed && mEnabled) {
                __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                                    "HBM watchdog expired; restoring normal panel mode");
                if (setPanelMode(kPanelModeNormal)) {
                    mEnabled = false;
                    ++mGeneration;
                }
            }
        }
    }

    std::mutex mMutex;
    std::condition_variable mCondition;
    bool mEnabled = false;
    bool mStopping = false;
    uint64_t mGeneration = 0;
    std::thread mWatchdog;
};

}  // namespace

int main() {
    setPanelMode(kPanelModeNormal);

    ABinderProcess_setThreadPoolMaxThreadCount(1);
    const std::shared_ptr<UdfpsHbm> service = ndk::SharedRefBase::make<UdfpsHbm>();
    const binder_status_t status =
            AServiceManager_addService(service->asBinder().get(), kServiceName);
    if (status != STATUS_OK) {
        __android_log_print(ANDROID_LOG_FATAL, LOG_TAG,
                            "Failed to register %s: status=%d", kServiceName, status);
        return 1;
    }

    ABinderProcess_joinThreadPool();
    return 1;
}
