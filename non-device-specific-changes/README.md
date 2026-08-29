# RoadSTR non-device-specific changes

This directory carries small, reviewable patches for shared AOSP, Lineage,
QCOM, and external repositories. They keep RoadSTR-specific behavior out of
shared repository history while giving other device maintainers reusable
references.

These are behavioral deltas, not exact-base requirements. Each patch records
its original commit SHA in the mail header. Apply it to the matching source
repository, then port the same intent when source context differs.

## Layout

- `frameworks-native/` SurfaceFlinger touch-boost policy and refresh-rate
  indicator correctness.
- `frameworks-av/` media ABI and vendor-variant compatibility.
- `frameworks-base/` UDFPS, adaptive-brightness, and predictive-back fixes.
- `frameworks-hardware-interfaces/` sensor-manager export.
- `frameworks-opt-telephony/` Qualcomm NR registration compatibility.
- `hardware-lineage-interfaces/` UDFPS HBM interface.
- `hardware-qcom-caf-sm8750-audio-primary-hal/` QTI primary audio integration.
- `hardware-qcom-caf-sm8750-display-core/` HDR GPU target handling.
- `hardware-qcom-caf-sm8750-display-hal/` composer memory compatibility.
- `external-tinyxml2/` CFI workaround for the stock-linked library.
- `packages-apps-Launcher3/` custom icon-shape launch animation alignment.
- `packages-apps-InfinitySuite/` native Settings submenu navigation.
- `packages-apps-Settings/` HAL-owned face enrollment preview.
- `packages-apps-ThemePicker/` framework-wide adaptive icon shape projection.
- `packages-apps-EuiccPolicy/` LPA without Google Play services.
- `vendor-google-gms/` optional Google Wallpaper Picker exclusion.

See [PATCHES.md](PATCHES.md) for the complete catalog and [APPLY.md](APPLY.md)
for application and porting instructions.
