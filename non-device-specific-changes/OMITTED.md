# Deliberately omitted changes

Former scan contained commits that are debug-only, reverted, unrelated, or
better owned by the device tree. They are intentionally absent from this
bundle.

## Debug-only

Early boot tracing in `system/core`, `system/libvintf`, `system/security`,
`system/sepolicy`, and `system/vold` was temporary diagnosis. Use device init,
bootconfig logging, recovery logs, and service-specific logcat instead.

## Reverted or unrelated

The `frameworks/base` ALS iterations and their reverts, WM Shell back-scrim,
`build/release` ranging permission, and `packages/apps/Stk` restyle do not
belong in RoadSTR build patches.

## Feature-only

Assist Button changes across `frameworks/base`, `lineage-sdk`, and
`packages/apps/LineageParts`, plus Launcher3/ThemePicker icon-shape changes,
are optional product features. Device overlays or keylayout can cover limited
appearance/input needs; omit shared policy and settings changes unless the
feature is explicitly required.

## Speculative display workaround

`hardware/qcom-caf/sm8750/display/core` `1a2d161db5be72211951662eb2f4a4da16159cc9`
was not shown to fix a reproducible RoadSTR issue. It is excluded. Re-add only
with an A/B reproduction, measured failure, and clear rollback criteria.

## Removed refresh-rate experiments

The SurfaceFlinger touch-boost ceiling (`2ac140659ff3`) and rounded ceiling
comparison (`9ea75bb86e91`) were removed. RoadSTR's ceiling was 120 Hz, already
the panel maximum, so neither changed mode selection. The broad Qualcomm
dynamic-refresh experiment (`3f8c1e52b66f`) was also removed: it mixed cleanup,
retry, validation, forced-GPU, and per-frame logging changes; generated heavy
SDM warning traffic; and did not exercise its proposed recovery paths.

## Unrelated framework permissions

The former `frameworks/base/0007-compat-unflag-permissions.patch` changed
platform feature-flag policy for ranging, system preferences, and promoted
notifications. RoadSTR has no device-specific consumer or demonstrated failure
requiring it, so it is excluded.

## Visibility-only experiments

XMP-Toolkit-SDK, google-highway, and Skia visibility changes had no live
RoadSTR consumer in the validated build. They were reverted and are excluded.
