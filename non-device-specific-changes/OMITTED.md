# Deliberately omitted changes

Former scan contained commits that are debug-only, reverted, unrelated, or
better owned by the device tree. They are intentionally absent from this
bundle.

## Debug-only

Early boot tracing in `system/core`, `system/libvintf`, `system/security`,
`system/sepolicy`, and `system/vold` was temporary diagnosis. Use device init,
bootconfig logging, recovery logs, and service-specific logcat instead.

## Reverted or unrelated

The `frameworks/base` ALS iterations and their reverts, `build/release` ranging
permission, and `packages/apps/Stk` restyle do not belong in RoadSTR build
patches. The WM Shell predictive-back scrim fix is retained in this bundle.

## Feature-only

Assist Button changes across `frameworks/base`, `lineage-sdk`, and
`packages/apps/LineageParts` are optional product features. Device overlays or
keylayout can cover limited appearance/input needs; omit shared policy and
settings changes unless the feature is explicitly required.

ThemePicker global icon-shape overlay `44da4135` and Launcher3 launch-radius
patch `f1aeba52` are retained in this bundle. Launcher3 delayed-reveal patch
`ec5c709a` remains omitted because it is an independent animation refinement.

## Speculative display workaround

`hardware/qcom-caf/sm8750/display/core` `1a2d161db5be72211951662eb2f4a4da16159cc9`
was not shown to fix a reproducible RoadSTR issue. It is excluded. Re-add only
with an A/B reproduction, measured failure, and clear rollback criteria.

## Visibility-only experiments

XMP-Toolkit-SDK, google-highway, and Skia visibility changes had no live
RoadSTR consumer in the validated build. They were reverted and are excluded.
