/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "RoadstrAntiFlicker"

#include <aidl/vendor/lineage/livedisplay/BnAntiFlicker.h>
#include <android-base/file.h>
#include <android-base/properties.h>
#include <android-base/strings.h>
#include <android/binder_auto_utils.h>
#include <android/binder_ibinder.h>
#include <android/binder_manager.h>
#include <android/binder_parcel.h>
#include <android/binder_process.h>
#include <android/log.h>
#include <sys/system_properties.h>

#include <chrono>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

namespace {

constexpr char kPanelService[] = "com.motorola.hardware.display.panel.IDisplayPanel/default";
constexpr char kPanelDescriptor[] = "com.motorola.hardware.display.panel.IDisplayPanel";
constexpr char kPanelDcPath[] = "/sys/class/drm/card0-DSI-1/panelDC";
constexpr char kPanelDpmsPath[] = "/sys/class/drm/card0-DSI-1/dpms";
constexpr char kRequestedModeProperty[] = "persist.vendor.roadstr.dc_enabled";
// DisplayPowerController publishes Display.STATE_* here. This public debug
// property provides blocking change notifications without periodic wakeups.
constexpr char kScreenStateProperty[] = "debug.tracing.screen_state";

// Confirmed in stock V2 BpDisplayPanel::setDcMode: transaction 4, int32
// PanelDcMode (OFF=0, ON=1), private-vendor flag, status header + int32 result.
// Keep the stock ABI local instead of changing shared Motorola interfaces.
constexpr transaction_code_t kSetDcModeTransaction = FIRST_CALL_TRANSACTION + 3;
constexpr uint32_t kPrivateVendorFlag = 0x10000000;

void* onCreate(void* args) { return args; }
void onDestroy(void*) {}
binder_status_t onTransact(AIBinder*, transaction_code_t, const AParcel*, AParcel*) {
    return STATUS_UNKNOWN_TRANSACTION;
}

class AntiFlicker final : public aidl::vendor::lineage::livedisplay::BnAntiFlicker {
  public:
    AntiFlicker() : mRequested(android::base::GetBoolProperty(kRequestedModeProperty, false)) {}

    ndk::ScopedAStatus getEnabled(bool* enabled) override {
        std::lock_guard lock(mMutex);
        *enabled = false;
        std::string value;
        if (!android::base::ReadFileToString(kPanelDcPath, &value)) {
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                    -1, "Cannot read panel DC mode");
        }
        value = android::base::Trim(value);
        if (value != "0" && value != "1") {
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(-1,
                                                                           "Invalid panel DC mode");
        }
        *enabled = value == "1";
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus setEnabled(bool enabled) override {
        std::lock_guard lock(mMutex);
        if (!android::base::SetProperty(kRequestedModeProperty, enabled ? "1" : "0")) {
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                    -1, "Cannot save requested DC mode");
        }
        mRequested = enabled;
        // An off panel cannot apply the DRM property. Restore it on wake.
        if (enabled && !isPanelOn()) return ndk::ScopedAStatus::ok();
        return applyRequestedLocked();
    }

    void monitorDisplay() {
        if (!android::base::WaitForPropertyCreation(kScreenStateProperty)) return;
        const prop_info* info = __system_property_find(kScreenStateProperty);
        if (!info) return;
        bool wasOn = false;
        for (;;) {
            const uint32_t serial = __system_property_serial(info);
            const bool on = android::base::GetProperty(kScreenStateProperty, "") == "2";
            if (on && !wasOn) {
                // Screen state is published before panel power-up completes.
                for (int attempt = 0; attempt < 5; ++attempt) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(300));
                    if (android::base::GetProperty(kScreenStateProperty, "") != "2") break;
                    std::lock_guard lock(mMutex);
                    if (isPanelOn() && applyRequestedLocked().isOk()) break;
                }
            }
            wasOn = android::base::GetProperty(kScreenStateProperty, "") == "2";
            uint32_t updatedSerial;
            __system_property_wait(info, serial, &updatedSerial, nullptr);
        }
    }

  private:
    static bool isPanelOn() {
        std::string state;
        return android::base::ReadFileToString(kPanelDpmsPath, &state) &&
               android::base::Trim(state) == "On";
    }

    ndk::ScopedAStatus applyRequestedLocked() {
        if (mRequested) {
            // The driver clears DC on power-up, but the stock HAL caches ON.
            // An OFF/ON pair is necessary to invalidate that cached state.
            auto off = setHardwareMode(false);
            if (!off.isOk()) return off;
        }
        return setHardwareMode(mRequested);
    }

    static ndk::ScopedAStatus setHardwareMode(bool enabled) {
        // Resolve each request so a restarted panel HAL never leaves a stale
        // proxy. Do not block a framework Binder thread waiting for the HAL.
        ndk::SpAIBinder panel(AServiceManager_checkService(kPanelService));
        static const AIBinder_Class* clazz =
                AIBinder_Class_define(kPanelDescriptor, onCreate, onDestroy, onTransact);
        if (!panel.get() || !AIBinder_associateClass(panel.get(), clazz)) {
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(-1,
                                                                           "Panel HAL unavailable");
        }

        AParcel* rawIn = nullptr;
        binder_status_t status = AIBinder_prepareTransaction(panel.get(), &rawIn);
        ndk::ScopedAParcel in(rawIn);
        if (status == STATUS_OK) status = AParcel_writeInt32(in.get(), enabled ? 1 : 0);
        AParcel* rawOut = nullptr;
        if (status == STATUS_OK) {
            rawIn = in.release();
            status = AIBinder_transact(panel.get(), kSetDcModeTransaction, &rawIn, &rawOut,
                                       kPrivateVendorFlag);
        }
        ndk::ScopedAParcel out(rawOut);
        if (status != STATUS_OK) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "DC mode transaction failed: %d",
                                status);
            return ndk::ScopedAStatus::fromStatus(status);
        }

        AStatus* rawStatus = nullptr;
        status = AParcel_readStatusHeader(out.get(), &rawStatus);
        ndk::ScopedAStatus reply(rawStatus);
        if (status != STATUS_OK) return ndk::ScopedAStatus::fromStatus(status);
        if (!reply.isOk()) return reply;
        int32_t result = -1;
        status = AParcel_readInt32(out.get(), &result);
        if (status != STATUS_OK) return ndk::ScopedAStatus::fromStatus(status);
        if (result != 0) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Panel HAL rejected DC mode %d: %d",
                                enabled, result);
            return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(
                    result, "Panel HAL rejected DC mode");
        }
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "DC mode %s", enabled ? "on" : "off");
        return ndk::ScopedAStatus::ok();
    }

    std::mutex mMutex;
    bool mRequested;
};

}  // namespace

int main() {
    // LiveDisplay owns the user preference. Retain the last requested hardware
    // mode so panel power-up and HAL restarts can restore it independently.
    ABinderProcess_setThreadPoolMaxThreadCount(1);
    ABinderProcess_startThreadPool();
    // Both services start in class hal. Wait on the startup thread before
    // advertising support so LiveDisplay's first restore cannot race the HAL.
    const ndk::SpAIBinder panel(AServiceManager_waitForService(kPanelService));
    if (!panel.get()) return 1;
    const auto service = ndk::SharedRefBase::make<AntiFlicker>();
    const std::string instance = std::string(AntiFlicker::descriptor) + "/default";
    const binder_status_t status =
            AServiceManager_addService(service->asBinder().get(), instance.c_str());
    if (status != STATUS_OK) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Cannot register %s: %d", instance.c_str(),
                            status);
        return 1;
    }
    // Hold the service for the lifetime of this blocking property watcher.
    std::thread([service] { service->monitorDisplay(); }).detach();
    ABinderProcess_joinThreadPool();
    return 1;
}
