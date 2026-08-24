# Patch catalog

Status labels:

- **RETAIN**: required by the current RoadSTR stack or fixes tested behavior.
- **OPTIONAL**: feature or panel policy; apply only when device configuration
  needs it.
- **COMPANION**: shared patch needs a device-tree or product definition.

## Frameworks native

### RETAIN: SurfaceFlinger cross-group touch boost

**Source:** `frameworks/native`  
**Original:** `0d9dd943ced8eb033f553a6dd9cbe8132db6ca74`  
**Patch:** `frameworks-native/0001-surfaceflinger-cross-group-touch-boost.patch`

Allows opted-in touch boost to rank modes across refresh-rate groups. RoadSTR
needs this because its 60 Hz mode and 90/120 Hz modes use different groups.
Companion product properties:
`ro.surface_flinger.touch_boost_across_groups=true`.

### RETAIN: SurfaceFlinger touch-boost ceiling

**Source:** `frameworks/native`  
**Original:** `1fdacd24c1d96126d3bfce9c924940a0f79a9377`  
**Patch:** `frameworks-native/0002-surfaceflinger-touch-boost-cap.patch`

Adds `ro.surface_flinger.touch_boost_refresh_rate`. RoadSTR uses 90 Hz to
avoid a panel luminance discontinuity during transient boost. Zero or unset
keeps prior behavior. Explicit layer or app votes remain uncapped.

## Media and graphics framework

### RETAIN: Legacy HGraphicBufferProducer ABI

**Source:** `frameworks/av`  
**Original:** `290272d973c0b83a864c85cd54d1e23b190bb496`  
**Patch:** `frameworks-av/0001-bqhelper-restore-legacy-abi.patch`

Restores ABI consumed by RoadSTR stock media components.

### RETAIN: Codec2 vendor variants

**Source:** `frameworks/av`  
**Original:** `49e34baf3bd558bf1ef8d3a4ad2f7f210dae1022`  
**Patch:** `frameworks-av/0002-codec2-disable-aosp-vendor-variants.patch`

Prevents incompatible AOSP vendor Codec2 variants from shadowing stock blobs.

### RETAIN: 64-bit OMX service

**Source:** `frameworks/av`  
**Original:** `aac6ba74b8e6745730fe1001f10d75bab8c5c6d1`  
**Patch:** `frameworks-av/0003-mediacodec-enable-omx-64bit.patch`

Enables OMX service support on 64-bit-only targets.

### RETAIN: HDR GPU target

**Source:** `hardware/qcom-caf/sm8750/display/core`  
**Original:** `99fe5d2cf66403449810dfccc189ac798712bcdc`  
**Patch:** `hardware-qcom-caf-sm8750-display-core/0001-display-hdr-gpu-target.patch`

Honors the HDR GPU target property used by the RoadSTR display stack.

### RETAIN: Composer memory compatibility

**Source:** `hardware/qcom-caf/sm8750/display/hal`  
**Original:** `175d2b317610b036f4a2e35952ff8c033c655f9e`  
**Patch:** `hardware-qcom-caf-sm8750-display-hal/0001-display-composer-memory-compat.patch`

Adds composer compatibility required by RoadSTR stock display allocations.

Keep display-core and display-hal patches separate so each can be tested and
ported independently.

## UDFPS, brightness, and sensors

### RETAIN: UDFPS validation and callback ordering

**Source:** `frameworks/base`  
**Originals:** `31ed8311e532fc4c66203b971ff9b357b063991d`,
`62bd1a7cea14c7b6389e7beaabff5294bba7dbd2`  
**Patches:** `frameworks-base/0001-systemui-validate-udfps-config.patch`,
`frameworks-base/0002-systemui-udfps-illumination-order.patch`

Rejects invalid UDFPS configuration and draws illumination before the ready
callback, matching the stock sensor sequence.

### RETAIN: UDFPS HBM service

**Sources:** `frameworks/base`, `hardware/lineage/interfaces`  
**Originals:** `a74a5ce22d6b93ff2ae1dea5734fbef76a60f60d`,
`bdcb6f711b3daf88e91a6cf0a91dd407561aeb10`  
**Patches:** `frameworks-base/0003-systemui-optional-udfps-hbm.patch`,
`hardware-lineage-interfaces/0001-udfps-hbm-interface.patch`

Adds optional HBM service contract. Requires matching device HAL/service and
VINTF declarations; omit both patches when HBM is not used.

### RETAIN: Adaptive-brightness hooks

**Source:** `frameworks/base`  
**Originals:** `00b4a8653d0c745a05fdbdb37143e0c3afe9eedf`,
`9a9caba6fa2492e3d2c75d622d8a7ddffb3395ce`,
`e9fdc7201c4d6622ee6439916de5833e45a1e3f`  
**Patches:** `frameworks-base/0004-brightness-retain-applied-nits.patch`,
`frameworks-base/0005-brightness-ambient-lux-hook.patch`,
`frameworks-base/0006-brightness-target-aware-lux.patch`

Retains applied-nits state and exposes ambient-lux and target-aware lux hooks
for the RoadSTR brightness implementation.

### RETAIN: Sensor manager export

**Source:** `frameworks/hardware/interfaces`  
**Original:** `3caaebc3243b36bed644dc37c0af95784116d58b`  
**Patch:** `frameworks-hardware-interfaces/0001-sensor-manager-export.patch`

Exports the current sensor manager instance used by the device sensor path.

## Qualcomm audio

### RETAIN: QTI primary HAL integration

**Source:** `hardware/qcom-caf/sm8750/audio/primary-hal`  
**Original:** `9ead09d45de3c9e5723b810789bc350f6fb3c169`  
**Patch:** `hardware-qcom-caf-sm8750-audio-primary-hal/0001-primary-hal-roadstr-integration.patch`

Connects the QTI primary audio service expected by RoadSTR stock components.
Keep complete stock `audiohalservice.qti` core implementations when using this
integration; do not combine it with a half-replaced source HAL.

## External and applications

### RETAIN: TinyXML2 CFI workaround

**Source:** `external/tinyxml2`  
**Original:** `98f3a9a40d219a043b14540637bd2bf5d4a31de7`  
**Patch:** `external-tinyxml2/0001-tinyxml2-disable-cfi.patch`

Disables CFI for the stock-linked tinyxml2 module. Apply with the common-tree
companion below. The common patch keeps `libtinyxml2_vendor` out of RoadSTR,
leaving global AOSP `libtinyxml2.so` and private `poweropt/libtinyxml2.so`.

### COMPANION: RoadSTR-only tinyxml package guard (common-tree commit)

**Source:** `device/motorola/sm7750-common`  
**Original:** `0f37846e138900a2fcf0c32521740fe658339a21`  
**Commit:** `0f37846e138900a2fcf0c32521740fe658339a21` in
`device/motorola/sm7750-common` (committed directly; no copy in this folder)

Sets common product to add `libtinyxml2_vendor` only when
`ROADSTR_HAS_NO_FM_TUNER` is unset. RoadSTR sets this variable in
`lineage_roadstr.mk`; other SM7750 devices keep existing behavior.

### RETAIN: Face enrollment preview

**Source:** `packages/apps/Settings`  
**Original:** `4b2f7d08a71befe5c840d9fc20a230f51ae1bd33`  
**Patch:** `packages-apps-Settings/0001-settings-face-enrollment-preview.patch`

Uses the HAL-owned preview path needed by RoadSTR face enrollment.

### RETAIN: eUICC without GMS

**Source:** `packages/apps/EuiccPolicy`  
**Original:** `df4707ecbd70b68a1416b64c19ce7978658dcb6c`  
**Patch:** `packages-apps-EuiccPolicy/0001-euiccpolicy-no-gms-gate.patch`

Allows LPA policy when Google Play services are absent. Apply only for builds
that ship and use eUICC without GMS.
