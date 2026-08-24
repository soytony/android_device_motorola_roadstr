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
3. Set both SurfaceFlinger properties and keep RoadSTR `RefreshRateDefaults`
   initialization when using the refresh patches.
4. Use the audio patch only with a complete stock `audiohalservice.qti`
   implementation and matching blobs.
5. Keep display-core and display-hal changes independently testable.

## Verification

```bash
git diff --check
git log --oneline -n 1
adb shell getprop sys.boot_completed
adb shell dumpsys SurfaceFlinger
adb shell ls -l /vendor/lib64/libtinyxml2*
adb shell ls -l /vendor/lib64/poweropt/libtinyxml2.so
```

For tinyxml validation, RoadSTR should lack
`/vendor/lib64/libtinyxml2_vendor.so`, retain global AOSP
`/vendor/lib64/libtinyxml2.so`, and retain private
`/vendor/lib64/poweropt/libtinyxml2.so`.
