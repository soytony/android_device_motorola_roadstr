# Motorola Edge 70 (roadstr)

Device tree for Motorola Edge 70 (XT2601-2), codename roadstr.

SoC: Qualcomm Snapdragon 7 Gen 4 (SM7750), platform "sun"
Android: 16 (SDK 36), vendor SDK 35
Kernel: GKI Linux 6.6.82-android15-8

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
