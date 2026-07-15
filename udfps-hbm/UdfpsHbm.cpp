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
constexpr char kMotoFingerprintService[] =
        "com.motorola.hardware.biometric.fingerprint.IMotoFingerPrint/default";
constexpr char kMotoFingerprintDescriptor[] =
        "com.motorola.hardware.biometric.fingerprint.IMotoFingerPrint";

// Stable NDK AIDL transaction number for IDisplayPanel.setMode(PanelMode).
constexpr transaction_code_t kSetModeTransaction = FIRST_CALL_TRANSACTION + 4;
// RoadSTR exposes this vendor fingerprint endpoint through the stable AIDL
// binder manager despite sharing the Motorola interface name with older HIDL.
// RoadSTR's deployed service routes sendFodEvent through transaction 1.
// Other transaction numbers crash the vendor HAL, so keep this isolated from
// the older Motorola interface ordering used by Rtwo.
constexpr transaction_code_t kSendFodEventTransaction = FIRST_CALL_TRANSACTION;
// Use a normal synchronous Binder call; the panel-specific private flag is
// rejected by this Motorola AIDL endpoint.
constexpr uint32_t kBinderFlags = 0;
constexpr uint32_t kPanelBinderFlags = 0x10000000;

// Values from Motorola's PanelMode enum, confirmed against the running stock service.
constexpr int32_t kPanelModeNormal = 0;
constexpr int32_t kPanelModeHighBrightFod = 4;
constexpr int32_t kMotoFodFingerDown = 1;
constexpr int32_t kMotoFodFingerUp = 0;
constexpr auto kHbmWatchdogTimeout = std::chrono::seconds(10);

ndk::SpAIBinder gPanel;
ndk::SpAIBinder gMotoFingerprint;

void* onBinderCreate(void* args) {
    return args;
}

void onBinderDestroy(void*) {}

binder_status_t onBinderTransact(AIBinder*, transaction_code_t, const AParcel*, AParcel*) {
    return STATUS_UNKNOWN_TRANSACTION;
}

const AIBinder_Class* getMotoFingerprintClass() {
    static const AIBinder_Class* clazz =
            AIBinder_Class_define(kMotoFingerprintDescriptor, onBinderCreate, onBinderDestroy,
                                  onBinderTransact);
    return clazz;
}

bool sendFodEvent(int32_t event) {
    if (!gMotoFingerprint.get()) {
        gMotoFingerprint = ndk::SpAIBinder(AServiceManager_waitForService(kMotoFingerprintService));
        if (!gMotoFingerprint.get()) {
            gMotoFingerprint = nullptr;
        } else if (!AIBinder_associateClass(gMotoFingerprint.get(), getMotoFingerprintClass())) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                                "Motorola fingerprint service has an unexpected interface");
            gMotoFingerprint = nullptr;
        }
    }
    if (!gMotoFingerprint.get()) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "Motorola fingerprint service is unavailable");
        return false;
    }

    AParcel* rawIn = nullptr;
    binder_status_t status = AIBinder_prepareTransaction(gMotoFingerprint.get(), &rawIn);
    ndk::ScopedAParcel in(rawIn);
    if (status == STATUS_OK) status = AParcel_writeInt32(in.get(), event);
    // The vendor method takes an optional byte[] event ID.  Encode it as null,
    // not an empty vector; the RoadSTR implementation rejects the latter.
    if (status == STATUS_OK) status = AParcel_writeInt32(in.get(), -1);
    AParcel* rawOut = nullptr;
    if (status == STATUS_OK) {
        rawIn = in.release();
        status = AIBinder_transact(gMotoFingerprint.get(), kSendFodEventTransaction, &rawIn,
                                   &rawOut, kBinderFlags);
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                            "Motorola FOD transact event=%d status=%d", event, status);
    }
    ndk::ScopedAParcel out(rawOut);
    if (status == STATUS_OK) {
        const binder_status_t transportStatus = status;
        AStatus* replyStatus = nullptr;
        const binder_status_t headerStatus = AParcel_readStatusHeader(out.get(), &replyStatus);
        const bool replyOk = headerStatus == STATUS_OK && replyStatus != nullptr &&
                AStatus_isOk(replyStatus);
        int32_t result = -1;
        if (replyStatus != nullptr) AStatus_delete(replyStatus);
        if (replyOk && AParcel_readInt32(out.get(), &result) != STATUS_OK) result = -1;
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                            "Motorola FOD event %d transport=%d header=%d vendor result=%d",
                            event, transportStatus, headerStatus, result);
        // The vendor implementation may return a non-standard AIDL reply
        // header. A completed synchronous Binder transaction is authoritative;
        // do not reject it solely because the optional result cannot be read.
        status = transportStatus;
    }
    const bool success = status == STATUS_OK;
    if (!success) gMotoFingerprint = nullptr;
    __android_log_print(success ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, LOG_TAG,
                        "Motorola FOD event %d: %s", event,
                        success ? "ok" : "error");
    return success;
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
                                   kPanelBinderFlags);
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

        // The vendor service expects the panel transition before the matching event.
        // FINGER_DOWN (0) follows HBM enable; FINGER_UP (1) follows HBM disable.
        const bool panelSuccess =
                setPanelMode(enabled ? kPanelModeHighBrightFod : kPanelModeNormal);
        const bool eventSuccess = sendFodEvent(enabled ? kMotoFodFingerDown : kMotoFodFingerUp);
        const bool success = panelSuccess && eventSuccess;
        if (enabled && !eventSuccess) {
            setPanelMode(kPanelModeNormal);
        }
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
