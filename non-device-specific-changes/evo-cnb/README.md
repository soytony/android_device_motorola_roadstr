# Evolution-X cnb / Android 17 series

This is the authoritative series for the shared working-tree changes captured
on 2026-10-18. It covers all 16 shared repositories identified in the audit.
The parent directories retain historical Lineage ports and optional features;
**do not apply those historical patches on top of this series**. This series
does not enable historical optional app features absent from the Evo delta.

`series.json` records the exact base, source commits, ordered patch paths and
expected final Git tree per repository. Every patch is generated from its
source commit with authorship and rationale. Shared commits are local; these
mail patches make them reproducible from the pushed device tree.

## Apply

Start with clean repositories at the recorded base commits, or port deliberately
to a different base. From the Android root, for each entry in `series.json`, run
`git -C <repo> am <absolute patch paths in listed order>`. Do not apply blindly
to an already patched checkout. Apply interface definitions before framework
consumers. Apply both vendor/lineage patches in order. The Soong heap-control
patch is optional host tuning; omit it when unnecessary (the recorded final
tree then intentionally differs). No runtime feature depends on that patch.

Run `python3 device/motorola/roadstr/non-device-specific-changes/evo-cnb/verify.py`
to apply patches in temporary Git indexes and compare exact final tree IDs.
This leaves source worktrees and indexes untouched. The verifier needs the
recorded base objects in each matching checkout; it does not fetch or reset.

## Evo reconciliation

Seven historical patches failed reverse checks because this port differs:

- frameworks-av/0002: Evo Codec2 defaults require an explicit AIDL vendor
  availability override; the current series carries that override.
- frameworks-base/0003: HBM wiring follows Evo's current SystemUI dependency
  locations. Optional empty service configuration retains default behavior.
- frameworks-base/0005 and 0006: the ambient-lux hook and target-aware processing
  are combined in the current brightness port, with the device implementation
  and parameters still supplied by device resources/code.
- frameworks-base/0009: shared minimum-refresh default remains Evo's 60 Hz,
  rather than the historical zero. RoadSTR's overlay selects 90 Hz. Settings
  reads the same resource as display policy.
- frameworks-native/0001: touch-boost policy is ported to the current selector
  context and paired with the device properties/cap.
- display-hal/0001: only composer retry/init changes apply. Obsolete libvmmem
  files are absent from this base and are intentionally not resurrected.

## Scope and companions

Generic hooks are needed where device code cannot export a platform ABI or
alter a private framework policy. Defaults stay unchanged where a hook allows
it. Codec2 module availability, 64-bit OMX and tinyxml2 CFI changes affect shared
modules and require review for other products. Keep the stock provider choices
and extraction fixups from both Motorola device/vendor trees together.

Motorola policy changes cover inherited stock services and concrete sysfs paths;
review them before reuse. The RoadSTR eUICC allowlist entry remains in the shared
package that owns the resource; prefer a product overlay if an equivalent
supported override becomes available. No new relocation experiment is included
in this tested source checkpoint.

Preserve RoadSTR Lunaris UI/resources/permissions with stock Motorola 64-bit
Dolby HALs. Xiaomi generic ODM Dolby blobs are incompatible replacements.
The primary audio source API port is build compatibility evidence, not proof
that the source HAL runs; RoadSTR's intended runtime HAL remains stock.

## Validation and rollback

All 26 patches passed sequential cached application and produced the exact
recorded final trees. Existing validation: full baseline attempt31 built;
subsequent system/vendor images booted with SELinux Enforcing; speaker/ringtone
were confirmed audible; attempt40 camera rear/front/rear capture, review, Home
and reopen passed three cycles. These are combined device-stack checks, not
isolated A/B proof for every historical shared change. The full attempt31 OTA
is stale and does not include the final fixes. Detailed evidence and limitations
are in `documents/roadstr-audio-camera-fix.md` and
`documents/roadstr-camera-after-shot-fix.md` in the source checkout.

Rollback a repository by reverting its listed commits in reverse order, together
with dependent device settings/provider changes. Do not remove a framework or
interface dependency while its device consumer is enabled. No new ROM build is
claimed for the documentation/catalog-only updates.

## Ordered catalog

### `build/soong`

Base: `1ac47f839dbb998440f558d886ba03a61954cfe8`. Result: `179c70eef0a9cae6070b4e37332032108a4eee49`.

- [build: allow platform resource APK installation](build-soong/0001-allow-platform-resource-APK-installation.patch)

  Motorola framework resources must share the framework directory. Expose an opt-in property instead of hard-coding another device module.

- [build: forward optional Go heap controls](build-soong/0002-forward-optional-Go-heap-controls.patch) — optional host tuning

  Constrained build hosts need graph-builder heap limits. Unset controls preserve the existing environment and have no device runtime effect.

### `external/tinycompress`

Base: `7a3ded281c0068322b89eb49438308fa5f46dfa1`. Result: `09e11d8ebd2548d90ffacee76136a81f9fef70de`.

- [build: export standalone tinycompress headers](external-tinycompress/0001-export-standalone-tinycompress-headers.patch)

  Stock prebuilt consumers still need matching public headers without selecting the source implementation.

### `external/tinyxml2`

Base: `33ba115074c80a720826efffab1937d5b355c3b8`. Result: `25e13c16acbd0b36b49103cedfaddd10736b14dd`.

- [fix: disable CFI for stock-linked tinyxml2](external-tinyxml2/0001-disable-CFI-for-stock-linked-tinyxml2.patch)

  Motorola vendor consumers require the stock-compatible library ABI. This affects the shared library; revisit when those blobs are retired.

### `frameworks/av`

Base: `8e6f34b6babe951b980f77a449828749bcfafd2b`. Result: `1b911bfc89263594781f3b5afa1013c2d6d0d83c`.

- [fix: restore legacy buffer producer ABI](frameworks-av/0001-restore-legacy-buffer-producer-ABI.patch)

  Stock media blobs reference the pre-rename buffer producer symbol. Forward to the existing implementation without changing the new API.

- [build: keep Codec2 vendor providers stock](frameworks-av/0002-keep-Codec2-vendor-providers-stock.patch)

  RoadSTR packages matching stock V1 Codec2 providers. Avoid duplicate source vendor variants; retain platform implementations. This shared module availability change requires review for other device targets.

- [fix: support 64-bit OMX service and sandbox](frameworks-av/0003-support-64-bit-OMX-service-and-sandbox.patch)

  RoadSTR is 64-bit only. Pair the OMX service architecture with arm64 syscalls rather than the inherited arm32 sandbox policy. Other targets requiring 32-bit OMX must retain their original service configuration.

### `frameworks/base`

Base: `a5aa7df30cf60f5874880614741a716d4a1f4551`. Result: `2cb60ce75596cc9adf243d8e1036de86a511a621`.

- [fix: port optional UDFPS HBM integration](frameworks-base/0001-port-optional-UDFPS-HBM-integration.patch)

  Validate device overlays and sequence optical illumination through the optional stable HBM service. Empty configuration retains the default path. Pair with the Lineage UDFPS interface and RoadSTR panel service.

- [feat: expose device brightness and refresh hooks](frameworks-base/0002-expose-device-brightness-and-refresh-hooks.patch)

  Keep ambient-lux processing in device code behind an empty-by-default class hook. Retain applied nits and allow overlays to choose the minimum refresh default. Preserve Evo default 60 Hz; RoadSTR selects 90 Hz.

### `frameworks/hardware/interfaces`

Base: `9de6d032a248523c22d0bd86e224d1762355c41e`. Result: `bd8352dde84bfcae2986a0214f083968f8d90af3`.

- [fix: export legacy sensor manager accessor](frameworks-hardware-interfaces/0001-export-legacy-sensor-manager-accessor.patch)

  Stock sensor clients require the manager accessor symbol. Preserve existing behavior while restoring linkage.

### `frameworks/native`

Base: `49687694bd18a4a2df972f89b6717dbfce610721`. Result: `476adf1bf512720907551b6a24246b1bbb0daa77`.

- [fix: port configurable refresh touch boost](frameworks-native/0001-port-configurable-refresh-touch-boost.patch)

  Allow cross-group touch boost with a device-controlled cap. Pair with RoadSTR SurfaceFlinger properties and refresh overlays; review policy impact before enabling on other display stacks.

### `hardware/lineage/interfaces`

Base: `a0f3ada220c573a2252bcb506407b04dfb9b3de0`. Result: `c15c856c9789a4ff60c5d482ac0117340f7ad7e5`.

- [feat: add stable optical UDFPS HBM interface](hardware-lineage-interfaces/0001-add-stable-optical-UDFPS-HBM-interface.patch)

  SystemUI and the device panel service need a stable, narrow Binder contract for optical illumination. Include the frozen API snapshot.

### `hardware/motorola`

Base: `50e65e5fd3ede70dd6417a9dbfe0ce5d798c626e`. Result: `6335e56bac2277207c81c22a0fdb014bba0ca773`.

- [feat: support legacy double-tap input sensors](hardware-motorola/0001-support-legacy-double-tap-input-sensors.patch)

  Older Motorola input devices expose a different gesture event ABI. Keep support behind the legacy_double_tap build flag.

- [fix: restore MotoActions build dependencies](hardware-motorola/0002-restore-MotoActions-build-dependencies.patch)

  MotoActions uses internal platform APIs and queries phone state. Declare the library and permission required by the existing behavior.

- [build: declare legacy Motorola HIDL interfaces](hardware-motorola/0003-declare-legacy-Motorola-HIDL-interfaces.patch)

  Stock health/storage and TCMD blobs need matching prebuilt HIDL interface metadata and the Motorola package root.

- [fix: label stock Motorola vendor services](hardware-motorola/0004-label-stock-Motorola-vendor-services.patch)

  Stock battery, panel, touch, wireless and Dolby services need dedicated SELinux labels and scoped access. RoadSTR removes duplicate local labels. These rules live in the inherited Motorola policy; review concrete paths and service availability before reuse on another Motorola device.

- [fix: allow RoadSTR in Motorola eUICC policy](hardware-motorola/0005-allow-RoadSTR-in-Motorola-eUICC-policy.patch)

  The shared Motorola allowlist gates the stock eUICC flow. Add RoadSTR without changing other entries. This device-specific entry remains here because the shared package owns its resource; prefer a product overlay if upstream later provides an equivalent supported override.

### `hardware/qcom-caf/sm8750/audio/graphservices`

Base: `62591b81ddb14cb1ddecf2a8e7162f2082456793`. Result: `d0dc57f7ef74ff1e9cadd7de99f6caf4fcd8ebdf`.

- [fix: guard unavailable hypervisor map ioctls](hardware-qcom-caf-sm8750-audio-graphservices/0001-guard-unavailable-hypervisor-map-ioctls.patch)

  The stock kernel UAPI lacks optional assignment ioctls. Return AR_EUNSUPPORTED instead of failing the source build.

### `hardware/qcom-caf/sm8750/audio/pal`

Base: `c0f825a2320577b9069783947ceec764bef8f672`. Result: `e4d7b03a2053ad58d943915fe7dcb57cad1fce64`.

- [fix: correct PAL logging formats and types](hardware-qcom-caf-sm8750-audio-pal/0001-correct-PAL-logging-formats-and-types.patch)

  Android 17 compiler checks reject mismatched format arguments. Correct the logging calls while retaining -Werror.

### `hardware/qcom-caf/sm8750/audio/primary-hal`

Base: `31964dd4cb5824bb71cadb19b39d82d77bd8b5a7`. Result: `ec2fa0ad1d2a4515eecda26896a58096762b5b71`.

- [fix: pair audio services with stock providers](hardware-qcom-caf-sm8750-audio-primary-hal/0001-pair-audio-services-with-stock-providers.patch)

  Avoid duplicate default manifests and Bluetooth software registration. Requires the matching stock audiohalservice.qti implementation.

- [fix: adapt QTI audio core to Android 17 APIs](hardware-qcom-caf-sm8750-audio-primary-hal/0002-adapt-QTI-audio-core-to-Android-17-APIs.patch)

  Handle mmap descriptors, unsupported flush-from-frame requests, ACN channel counts and range-loop warnings. Source build validation does not establish this HAL as the runtime provider; RoadSTR uses stock HALs.

### `hardware/qcom-caf/sm8750/display/core`

Base: `89aa1914da50aae0fb1b2ebf3db38d34fba5f748`. Result: `a0b48ce7be580f6a0fd91e50b49211482c9836b0`.

- [fix: preserve HDR GPU target handling](hardware-qcom-caf-sm8750-display-core/0001-preserve-HDR-GPU-target-handling.patch)

  Retain the HDR target compatibility path required by the stock RoadSTR display stack.

### `hardware/qcom-caf/sm8750/display/hal`

Base: `9c00ef9bd5cc5799a441c13e442a09756b6db51e`. Result: `df9181966bc2657038f8b0b0200749958ac2ed46`.

- [fix: port composer retry and init compatibility](hardware-qcom-caf-sm8750-display-hal/0001-port-composer-retry-and-init-compatibility.patch)

  Retain composer compatibility on Evo without obsolete libvmmem hunks. Those files do not exist on this base and must not be reintroduced.

### `packages/apps/Settings`

Base: `717b365cd1f37743582487012741b2fb1fcaa4a5`. Result: `169d2b19aadf3eb6d9b5d973e1fb669d76d676ff`.

- [fix: honor device minimum refresh default](packages-apps-Settings/0001-honor-device-minimum-refresh-default.patch)

  Settings and display policy must use the same device resource rather than disagreeing on the initial minimum refresh rate.

### `vendor/lineage`

Base: `72104cd46a5e303e74e57977d5e995b9d4469b72`. Result: `baa3dd91178e50dccbdd23064d5e143cf3f8956f`.

- [build: skip empty generator dependencies](vendor-lineage/0001-skip-empty-generator-dependencies.patch)

  Optional build variables may expand to an empty dependency. Skip them instead of globbing the dependency root.

- [build: support prebuilt kernel header archives](vendor-lineage/0002-support-prebuilt-kernel-header-archives.patch)

  Stock-kernel devices supply exported UAPI headers without a source kernel build. Track the archive as an input and preserve source-kernel paths for products that do not opt in.
