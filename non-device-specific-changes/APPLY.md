# Applying and porting patches

Run commands from an Android source checkout. Apply each directory to its
matching repository; do not apply all directories from the top level.

## Exact or close base

```bash
cd frameworks/native
git am --3way /path/to/non-device-specific-changes/frameworks-native/*.patch
```

`git am` preserves authorship and creates normal commits. `--3way` tolerates
small base drift. Check first without changing history:

```bash
git apply --check --3way /path/to/patch.patch
```

## Conflict handling

After a conflict, inspect intent with the patch mail header and diff:

```bash
git status
git am --show-current-patch=diff
# edit conflicted files
git add path/to/resolved-file
git am --continue
```

Abort only the in-progress mail application with `git am --abort`.

## Different base or SHA

SHA equality is unnecessary. Find equivalent functions, properties, module
names, and call sites in the newer tree. Apply manually or with a reduced
context patch, then preserve the behavior described in `PATCHES.md`. Do not
force unrelated hunks merely to make a patch apply.

For a non-commit port, use `git apply --reject --whitespace=fix` only after
reviewing rejects. Record the destination commit and source SHA in your local
device-tree notes.

## Dependency order

1. Apply interface/framework patches before dependent HAL or app patches.
2. Use committed common-tree tinyxml guard `0f37846` in
   `device/motorola/sm7750-common` together with `external-tinyxml2`; common
   tree is part of device-tree ownership and has no duplicate patch here.
3. Set `ro.surface_flinger.touch_boost_across_groups=true` with the cross-group
   touch-boost patch. Do not restore the removed boost-cap property or
   `RefreshRateDefaults` cap experiment.
4. Keep RoadSTR's device overlay refresh policy separate from shared patches:
   default 90-120 Hz, low-light thresholds `56, 67` / `-1, 70`, and 90 Hz zone
   rate. Rebuild `product` after changing these resources.
5. Use the audio patch only with a complete stock `audiohalservice.qti`
   implementation and matching blobs.
6. Keep display-core and display-hal changes independently testable.
7. With GApps, apply the ThemePicker and `vendor/google/gms` patches together.
   Set `EXCLUDE_GOOGLE_WALLPAPER_PICKER := true` before common product
   inheritance so PackageManager loads AOSP ThemePicker's shared package.

## Verification

```bash
git diff --check
git log --oneline -n 1
adb shell getprop sys.boot_completed
adb shell dumpsys SurfaceFlinger
adb shell dumpsys display
adb shell ls -l /vendor/lib64/libtinyxml2*
adb shell ls -l /vendor/lib64/poweropt/libtinyxml2.so
adb shell pm path com.android.wallpaper
adb shell cmd overlay list | grep lineage_launcher_icon_shape
```

For tinyxml validation, RoadSTR should lack
`/vendor/lib64/libtinyxml2_vendor.so`, retain global AOSP
`/vendor/lib64/libtinyxml2.so`, and retain private
`/vendor/lib64/poweropt/libtinyxml2.so`.

For refresh-rate validation, compare SurfaceFlinger active mode and Qualcomm
SDM current FPS with the Developer Options overlay. The overlay should follow
60/90/120 without toggling after the cache-invalidation patch.
