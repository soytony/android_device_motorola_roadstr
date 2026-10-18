# Motorola Edge 70 (roadstr)

Device tree for Motorola Edge 70 (XT2601-2), codename roadstr.

SoC: Qualcomm Snapdragon 7 Gen 4 (SM7750), platform "sun"
Platform target: Evolution-X Android 17 (`cnb`)
Stock firmware base: Android 16 (SDK 36), vendor SDK 35
Kernel: GKI Linux 6.6.98-android15-8 (stock W1WRS36.39-115-2)

## Structure

- `AndroidProducts.mk` -- product entry point
- `lineage_roadstr.mk` -- product makefile
- `lineage.dependencies` -- repo manifest dependencies
- `device.mk` -- device-specific packages and configs
- `BoardConfig.mk` -- device-specific board config
- `audio/` -- audio platform configs
- `configs/` -- media profiles and other configs
- `nfc/` -- NFC configs (STMicro ST54SE)
- `rootdir/etc/` -- init scripts
- `sepolicy/vendor/` -- device-specific SELinux rules
- `overlay-lineage/` -- LineageOS RRO overlays
- `prebuilt/` -- prebuilt kernel image, DTB, DTBO

## Current status

- Build target: `lineage_roadstr-cp2a-userdebug`
- The `evo-cnb` branch starts from the Android 16 `lineage-23.2` device tree.
  Android 17 baseline build and incremental image boot validation passed.
  Speaker/ringtone and three rear/front/rear camera lifecycle cycles passed
  with SELinux Enforcing. The full baseline OTA predates the final fixes.
  Evidence: `documents/roadstr-audio-camera-fix.md` and
  `documents/roadstr-camera-after-shot-fix.md` in the Evolution-X checkout.
- Shared compatibility patches and exact source bases are recorded in
  [the Evo catalog](non-device-specific-changes/evo-cnb/README.md).
- Stock Motorola MotCamera5 is included from product blobs; Lineage Aperture is
  excluded from the RoadSTR product.
- Stock Moto/QTI IMS shared libraries are included; `org.codeaurora.ims` starts without the previous missing `QtiCarrierConfigHelper` crash.
