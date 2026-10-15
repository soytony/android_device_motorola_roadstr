# RoadSTR / SM7750 two-week backport audit

Audit date: 2026-10-15, Asia/Taipei. Window: October 1–15, 2026 (the preceding two weeks). Scope: all commits reachable from the fetched `origin/lineage-23.2` in the four device/vendor repositories during this window. Both author and committer dates of the eight included commits fall within the window. The initial review below records pre-backport findings. The implementation section records the subsequent user-authorized backports; its disposition supersedes the initial recommendations.

## Snapshot

All remotes are GitHub repositories owned by `soytony`; all four source and destination branches were fetched successfully.

| Repository | Current infinity-16 HEAD | origin/infinity-16 | Reviewed lineage-23.2 tip |
| --- | --- | --- | --- |
| android_device_motorola_roadstr | 2da7254b6642759605ff0dbf38218e7b679d8f9f | same | c276bca81555bd89a3d675ba73a6d4a1ec9e8912 |
| android_device_motorola_sm7750-common | edb3ed50ca6bb0bcf592ae3816fb35420f4645be | same | ddabce4d3c5201060667df7350d295a0457e2e2d |
| proprietary_vendor_motorola_roadstr | 8ad6fbc2eef9c8fcfa932901d28f19faaed79373 | 71bc1e83a15dbcbba3a67f777c226ef65fd36408 | 4cdc387f5d290058a12e5ae08f6229b627d54403 |
| proprietary_vendor_motorola_sm7750-common | 4a741919372d832500aae9b4a52f9f478321ca92 | same | 7296fc74b28be58d55f36ee839f79bfe4e11fbfb |

The RoadSTR vendor checkout has two local commits beyond its remote destination: `4eaf113` (stock CBOR override) and `8ad6fbc` (carrier extension removal). Review considers both checked-out builds and the remote branch; their CBOR state differs materially. Existing uncommitted display work was preserved: RoadSTR `lineage_roadstr.mk`, Frameworks overlay arrays/integers, and common `vendor.prop`.

## Every commit: verdict

| Repository | Commit / date | Change | Apply to InfinityX? |
| --- | --- | --- | --- | --- |
| device roadstr | [7210eb7d](https://github.com/soytony/android_device_motorola_roadstr/commit/7210eb7d2aa6ddef5a97d33d28db75071b789979), Oct 11 | MotoCamera DSP policy | **Adapt missing labels; do not cherry-pick wholesale.** Existing domain mapping and most post-processing labels are already present. |
| device roadstr | [464ac2bd](https://github.com/soytony/android_device_motorola_roadstr/commit/464ac2bd773b74618642a3ff8a45833efe6f17c1), Oct 13 | Default minimum refresh 90 Hz | **Port with framework/UI companions.** Device-only application is incomplete. |
| device roadstr | [141386fb](https://github.com/soytony/android_device_motorola_roadstr/commit/141386fb3f1c48fd5c859d77f7c01ef2e0d4b51b), Oct 13 | Attenuate top amplifier | **Apply with defec998; validate on InfinityX.** Device-specific, isolated from Android volume curves. |
| vendor common | [defec998](https://github.com/soytony/proprietary_vendor_motorola_sm7750-common/commit/defec998e1f8bf4f95172f50000515ec06554182), Oct 13 | TFA container override | **Apply with 141386fb.** Stock fallback preserves other products. |
| device common | [ddabce4d](https://github.com/soytony/android_device_motorola_sm7750-common/commit/ddabce4d3c5201060667df7350d295a0457e2e2d), Oct 13 | Reproducible private CBOR extraction | **Apply with 7296fc74.** Required to retain the fix after extraction. |
| vendor common | [7296fc74](https://github.com/soytony/proprietary_vendor_motorola_sm7750-common/commit/7296fc74b28be58d55f36ee839f79bfe4e11fbfb), Oct 13 | Private CBOR + rewritten Thales consumers | **Apply with ddabce4d and cleanup.** Prefer isolation over global stock CBOR replacement. |
| device roadstr | [c276bca8](https://github.com/soytony/android_device_motorola_roadstr/commit/c276bca81555bd89a3d675ba73a6d4a1ec9e8912), Oct 14 | Reduce refresh transitions | **Defer wholesale; evaluate a manual port separately.** Conflicts with active display mitigation; not a luminance fix. |
| vendor roadstr | [4cdc387f](https://github.com/soytony/proprietary_vendor_motorola_roadstr/commit/4cdc387f5d290058a12e5ae08f6229b627d54403), Oct 14 | ACDB replacement | **Hold pending calibration provenance and audio regression evidence.** Binary changes exceed a narrowly documented top-speaker adjustment. |

## Camera: retain existing domain, merge only missing policy

`infinity-16` already has camera fixes `d6badad` and `2a0f430`, from September. Current RoadSTR `seapp_contexts` maps `com.motorola.camera5` to `vendor_snapcam_app` with `levelFrom=all`; upstream adds the same package/domain with `levelFrom=user`. Do not append a second matching entry or change MLS behavior without a separate justification. The upstream commit does not explain the different `levelFrom` value.

Current RoadSTR policy already labels portrait repair, Arcsoft HDR/SN/motion, enginepostproc, postprocclient, mpbase, QnnHtp, and arcsoft_qnnhtp. It also has SR/distortion labels absent from the upstream replacement. Preserve these. In the inspected Qualcomm sm8750 common policy, OpenCL, fastcvopt, qti-perfd-client and the three FastRPC libraries are already `same_process_hal_file`; duplicating their expressions is unnecessary.

Among upstream public dependency labels, the currently packaged `libQnnHtpV73Stub.so` has no matching explicit label in inspected RoadSTR/common/sm8750 policy. Add its exact label after checking the actually selected merged policy. Upstream also includes `libQnnSystem.so`; no corresponding lib64 blob was found in these current vendor trees, so check packaging before adding a speculative rule. The QNN dependency label is the remaining useful part, rather than replacing all current labels.

Qualcomm sm8750 `SEPolicy.mk` includes the test policy containing `vendor_snapcam_app` only for userdebug/eng. The existing mapping already shares this limitation. If InfinityX release targets `user`, ensure the domain is supplied in production policy and run neverallow/seapp checks; this upstream patch alone does not solve that. Upstream's commit reports enforcing capture validation on BP4A, not on this InfinityX tree.

## Minimum refresh: port the policy and the InfinityX helper together

The new device RRO references `config_defaultMinRefreshRate`, absent from current `frameworks/base` config and symbols. The bundled framework patch supplies it and changes `DisplayModeDirector`'s missing-setting fallback. However, its context expects a `0f` fallback; InfinityX currently uses `60f` at `DisplayModeDirector.java:1184`. This needs a deliberate port with the existing behavior preserved on other devices.

The bundled Settings patch targets `com/android/settings/display/MinRefreshRatePreferenceController.java`, which does not exist here. InfinityX uses `com/infinity/settings/display/refreshrate/*` and `frameworks/base/core/java/com/android/internal/util/neoteric/DisplayRefreshRateHelper.java`. That helper currently derives its minimum default from `config_defaultRefreshRate` (lines 59–64), with a 60 Hz fallback and system-user setting access. Port the new minimum resource into the helper, keep peak/default render-rate semantics separate, and align user selection with `DisplayModeDirector`'s current-user behavior. Retain saved preferences.

The local uncommitted RRO changes `config_defaultRefreshRate` to 90, but that is not equivalent to the new minimum resource: `DisplayModeDirector` still defaults MIN_REFRESH_RATE to 60, while the UI helper would show 90. The default refresh resource also feeds the framework's separate default-rate vote (`DisplayModeDirector.java:1028`). Do not present this as a completed adaptive 90–120 policy.

The commit removes an unused RefreshRateDefaults permission copy, not an actual app implementation. No active RefreshRateDefaults module reference was found in device/product configuration. Remove the permission only as part of the complete replacement, and adapt the patch documentation to InfinityX's actual helper rather than shipping a nonapplicable Lineage Settings patch.

## TFA: useful paired audio fix

`141386fb` sets `ROADSTR_TFA_CONTAINER` before inheriting common configuration. `defec998` selects it in the existing firmware copy rule, falling back to stock when unset. Both are needed: the device commit alone packages no new container, and the vendor commit alone changes no RoadSTR audio. The existing variable placement is correct.

The upstream commit describes amplifier 0x34 speaker TDMSPKG 31, stock 8 restoration on profile exit, with amplifier 0x35 and handset profiles preserved. It reports notification/call validation on BP4A. This is upstream evidence, not a repeated InfinityX hardware test. Structural check: new container is 1,636 bytes, matches its length field, and has valid CRC32 `0x2cb67f31` over bytes 14 onward. Current stock container is 1,584 bytes. New SHA-256: `c59773a81f3a7dfeb270f9517391b5fb929e720079c3c033585d9360de3b7d6f`.

Recommendation: use this pair before introducing the separate ACDB change, so any audio regression can be attributed. Validate notification crackle, stereo balance at low/high volumes, media, earpiece call, speakerphone and profile exit/reentry on InfinityX. CRC does not prove amplifier profile semantics or acoustic safety.

## KeyMint: private ABI isolation is preferable

Current Thales service and libjc_keymint-thales both need `libcppbor_external.so`. The local RoadSTR vendor commit `4eaf113` installs a stock blob under that global name and overrides the platform module. This mitigates the stock consumers' ABI problem locally, but can supply an old ABI to other vendor consumers. The remote infinity-16 RoadSTR vendor branch lacks this override entirely.

Current platform `system/libcppbor/include/cppbor/cppbor.h` has virtual `asFloat`/`asDouble` before `encodedSize`, consistent with upstream's stated vtable incompatibility. The new common-vendor blobs both need `libcppbor_thales.so`; the new private library advertises that SONAME. All nine cppbor imports checked in the new KeyMint library resolve in the private library; the service has no directly named cppbor dynamic imports. The private library and currently installed stock override expose the same 48 checked cppbor symbols, but symbol equality alone does not establish vtable compatibility.

`ddabce4d` adds source-to-private-destination extraction, `fix_soname()`, and rewrites both consumers. `7296fc74` provides the actual blobs, Soong module and product package. Apply them together. Applying only extraction changes leaves current built blobs untouched; applying only vendor blobs loses the fix on future extraction.

On the checked-out local RoadSTR vendor branch, also remove the obsolete `libcppbor_external_stock` module/product package and its stock blob/extraction entry once all consumers are audited. The two upstream commits do not perform that RoadSTR cleanup. Keep any remaining independent stock dependency under an explicitly justified private name; do not remove a shared dependency blindly. On the remote destination there is no module to remove, but the device proprietary-files entry still warrants generator consistency review.

Upstream reports two successful first-PIN unlocks after reboot on BP4A. InfinityX release validation must repeat first unlock after reboot, shared-secret negotiation, Weaver/Gatekeeper and StrongBox-backed operations with the produced vendor image. Do not import unrelated init/Weaver differences from a whole-branch merge.

## Display-transition commit: conflicts and unresolved flicker

The current uncommitted InfinityX work selects a 500 ms idle timer, disables vendor idle switching in product and common properties, and restores stock low-light thresholds with a fixed 90 Hz zone. Upstream `c276bca8` instead changes its timer from 200 to 2,000 ms and adds `vendor.display.disable_dynamic_fps=1`. Its brightness DRM/DPPS/STC properties were already present in the upstream parent; they are context, not additions made by this commit. InfinityX lacks those context properties, so a manual backport must explicitly decide whether to adopt them.

Inspected sm8750 display source reads `disable_dynamic_fps` and blocks `ChangeFps`/`SetRefreshRate`; physical `SetActiveConfig` remains a separate path. This supports evaluating the new property to stop vendor overrides. It does not prove behavior of every packaged proprietary display component. A two-second timeout also keeps high refresh active longer and can affect power.

The commit explicitly documents unresolved CSOT NT37706 low-gray luminance differences and a release-time dip. No per-rate gamma/LUT correction is implemented. Recommendation: defer the full commit while current low-light work is being assessed; if testing its policy, manually port and reconcile all property sources, alongside a valid minimum-refresh implementation. Measure physical mode, brightness, low-gray luminance, touch release and power; confirm explicit 60/90/120 selection, adaptive 90/120 and AOD/HDR. Do not claim the timeout fixes flicker or overwrite local low-light mitigation implicitly.

## ACDB: insufficient evidence for an unconditional backport

`4cdc387f` changes only `QRD_kera_acdb_cal.acdb`; its commit message gives no stock build source, decoded calibration diff, affected routes, or runtime test. Both versions are 3,483,866 bytes with an ACDB header, but 26,224 byte positions differ. That cannot be interpreted reliably as gain-only changes from raw offsets. It could affect more than speaker playback.

Current SHA-256: `8b40089b36459fcedbee2afc616d7a540bf2f3eda7c4a170cf0ab9456b1c6283`.
New SHA-256: `fe24a1b3e69e2937e721328812736891b966faae35694bd421f98022da53c772`.
The file already has a vendor copy rule, so packaging is straightforward; compatibility and calibration correctness remain unproven. Hold it until source firmware/version or a decoded ACDB change record identifies the change, then test all affected playback/recording/call routes against this InfinityX audio stack. Keep this separate from the independently documented TFA fix.

## Verification and integration order

Read-only `git apply --check` against the current worktrees succeeds for `141386fb`, `defec998`, `ddabce4d`, `7296fc74`, and `4cdc387f`. It fails for camera (seapp file already exists), minimum refresh (documentation/RRO context), and transition policy (product property context). A clean apply only establishes patch compatibility, not runtime correctness. No patches were applied, no builds run, and no device flashed for this review.

Recommended implementation order:

1. Apply the KeyMint common-device/common-vendor pair and reconcile the local RoadSTR stock CBOR override.
2. Adapt the missing camera label while preserving current domain/MLS and existing labels; verify merged release policy.
3. Apply the TFA device/common-vendor pair and test it independently.
4. Port minimum-refresh resources, DisplayModeDirector and InfinityX DisplayRefreshRateHelper together, preserving saved settings and other-device defaults.
5. Evaluate transition policy only after resolving the current low-light policy choice; hold ACDB until its provenance/route changes are documented.

The audit covers every commit in the stated four-repository window. Recommendations are code/packaging findings with explicit runtime limits; passing InfinityX build and hardware gates remains work for any subsequent backport task.

## Implementation following user direction

User requested all eight changes, including the display-transition and ACDB commits, with conflicts resolved toward Lineage and each change committed separately. All eight were applied. Additional companion/cleanup changes were committed separately:

| Tree | Upstream/change | Destination commit |
| --- | --- | --- |
| device roadstr | 7210eb7d camera policy | 430db04 |
| frameworks/base | Configurable minimum resource and display policy | 859aa8c26e11 |
| frameworks/base | InfinityX Settings helper companion | ffbc3ea5147f |
| device roadstr | 464ac2bd minimum-refresh default | a16ee80 |
| vendor common | defec998 TFA override | 778a8a4 |
| device roadstr | 141386fb top-speaker container | a85566f |
| device common | ddabce4d Thales extraction | 2b57e9d |
| vendor common | 7296fc74 private Thales CBOR | b8f88d4 |
| device roadstr | c276bca8 display-transition policy | 329e90f |
| device common | Align common idle-switch property with Lineage | f322330 |
| vendor roadstr | 4cdc387f ACDB calibration | 0e494b2 |
| vendor roadstr | Retire global stock CBOR override | 3e09a88 |
| device roadstr | Remove redundant device CBOR extraction | 25cd55e |
| device roadstr | Deduplicate pre-existing camera labels | 42ac4cf |

Display conflict resolution uses Lineage's 2,000 ms idle timer, brightness DRM/DPPS/STC properties, `disable_dynamic_fps=1`, idle-switch value 0, minimum 90 Hz, peak 120 Hz, default render-rate 0 and zone rate 0. The uncommitted low-light thresholds were removed. Original uncommitted diffs are backed up in `/tmp/roadstr-backport-20261015-003518`; unrelated framework font edits remain uncommitted and unchanged.

Camera preserves existing nonconflicting library labels, adds Lineage's dependency expressions, and uses Lineage's `levelFrom=user` mapping. The shared minimum resource defaults to zero, matching Lineage's unconstrained framework policy; the InfinityX Settings helper reads the new resource with a 60 Hz fallback, matching the Lineage UI companion. No Settings repository change is needed because its controllers delegate to that shared helper.

Structural verification passes: resource XML parses, extraction script Python syntax compiles, RoadSTR file contexts compile with `sefcontext_compile`, and Lineage RRO integers/TFA/KeyMint/ACDB payloads match upstream bytes exactly. Device/vendor worktrees are clean. Soong/Make graph generation passed. The initial image build was stopped after it scheduled about 7,100 dependencies; validation switched to `framework-minus-apex services Settings FrameworksResRoadstr vendor_sepolicy.cil sepolicy_neverallows file_contexts.bin`. All targeted checks completed successfully in 23m23s (exit 0). Log: `/tmp/roadstr-backport-targeted-build.log`. Vendor seapp_contexts also built successfully during the initial image-target run, and strict standalone `checkseapp` passed. Final installation checks confirm the 2,000 ms timer, dynamic-FPS disable value 1, idle-switch value 0, matching TFA/ACDB contents, and the private Thales library. No full-image build completion or hardware validation is claimed; no device flashed.


## Bacon build and completed device flash

The full `infinity_roadstr-userdebug` bacon build completed in 2h08m35s.
The original OTA is `Project_Infinity-X-3.12-roadstr-15.10.2026-GAPPS-UNOFFICIAL.zip`,
SHA-256 `0d9cb57af2b0e546b08bbd058370d779e7c56d7a590aaa8d0b8560d5542fad09`.
Its CRC and canonical target-files AVB chain passed verification. Top-level
output images were stale; only canonical target-files images were used.

| Stage | Result |
| --- | --- |
| Stock recovery baseline | User-selected W1WRS36.39-115-2 package; all 36 manifest hashes passed; stock flash succeeded with userdata/metadata preserved |
| InfinityX physical images | Seven images flashed in bootloader with slot A active |
| Empty super | Reset successfully with canonical super_empty.img |
| Full super | User authorized bootloader flashing after fastbootd failed; fresh full image generated from canonical target files; all six embedded images matched expanded source hashes; all 27 transfer segments passed |
| Boot failure | Original vbmeta omitted pvmfw; full super alone did not resolve the rejection |
| AVB fix | Added the existing stock pvmfw descriptors, signed with the same key; retained flags 0 and root rollback index 1; complete AVB chain verification passed |
| Source fix | device/motorola/roadstr b5f5059: include stock pvmfw descriptors in future root vbmeta builds |
| Runtime | sys.boot_completed=1, slot _a, incremental 1792027735, InfinityX 3.12 |
| Display runtime | Idle timer 2000 ms, disable_dynamic_fps=1, disable_idle_fps_switch=0 |
| Data | No userdata or metadata erase performed |

Bootloader `oem read_sv` confirmed stored root rollback index 1. The pvmfw
hash fix allowed fastbootd and then Android to boot, without disabling AVB.
Android security-state/fingerprint properties are spoofed by this build and
were not treated as evidence that the bootloader is locked.

Fixed standalone image: `out/target/product/roadstr/vbmeta-pvmfw-fixed.img`.
The original OTA ZIP and original canonical vbmeta still predate this fix;
regenerate the OTA before using it for another installation. The source fix
was validated against all canonical images and on the phone; bacon was not
rerun after the fix. Final state and logs: `/tmp/roadstr-infinity-reflash-state.json`,
`/tmp/roadstr-avb-pvmfw-verify.log`, `/tmp/roadstr-avb-pvmfw-flash.log`,
`/tmp/roadstr-infinity-super-flash.log`, and `/tmp/roadstr-infinity-boot-verification.json`.
