# RoadSTR InfinityX build, flash, and AVB recovery — 2026-10-15

## Final result

InfinityX 3.12 booted successfully after the root vbmeta image was updated
to authenticate the stock pvmfw prerequisite. The source fix is committed as
`b5f5059b1c81af65f4640c7c7863ad99bbd6679d` in device/motorola/roadstr.

| Check | Verified result |
| --- | --- |
| Device | Motorola Edge 70 / RoadSTR, serial ZY22M6NVR4 |
| Boot completion | sys.boot_completed=1 |
| Active slot | _a |
| InfinityX version | 3.12 |
| Build incremental | 1792027735 |
| AVB configuration | Flags 0; root rollback index 1; pvmfw descriptor included |
| Data | Userdata and metadata preserved throughout |
| Display idle timer | ro.surface_flinger.set_idle_timer_ms=2000 |
| Dynamic FPS | vendor.display.disable_dynamic_fps=1 |
| Idle FPS switch | vendor.display.disable_idle_fps_switch=0 |

The Android fingerprint and verified-boot properties are spoofed by this ROM.
The actual bootloader reported securestate=flashing_unlocked. Those Android
properties do not establish hardware lock or verification state.

## Build and artifacts

Workspace: `/media/tony/SSD512/ProjectInfinityX`.
Build command: source build/envsetup.sh, lunch infinity_roadstr-userdebug,
then m -j8 bacon. Bacon completed successfully in 2h08m35s. The focused
framework/Settings/resource/SELinux build passed in 23m23s.

| Artifact | Record |
| --- | --- |
| Original OTA | out/target/product/roadstr/Project_Infinity-X-3.12-roadstr-15.10.2026-GAPPS-UNOFFICIAL.zip |
| Original OTA SHA-256 | 0d9cb57af2b0e546b08bbd058370d779e7c56d7a590aaa8d0b8560d5542fad09 |
| Fresh complete super | out/target/product/roadstr/super-infinity-20261015.img |
| Super SHA-256 | eb198cacb6c9131d970d70a42b23b47d59a6e6c62affa3789d2063032304e48d |
| Initial working root vbmeta (original recovery only) | out/target/product/roadstr/vbmeta-pvmfw-fixed.img |
| Initial vbmeta SHA-256 | db999cf17b04027c287f233a2ea99343baca1b5fc22615e9342d425b2dccc0ab |

Canonical build images came from:
`out/target/product/roadstr/obj/PACKAGING/target_files_intermediates/infinity_roadstr-target_files/IMAGES`.
Some top-level output images were stale, including the old October 7 super.img.
The fresh full super was generated with the build_super_image host tool from
canonical extracted target files. All six embedded logical partitions were
unpacked and matched the expanded source-image SHA-256 hashes.

**The original OTA ZIP and original canonical vbmeta predate the pvmfw fix.**
Bacon was not rerun after that fix. Rebuild the OTA with commits b5f5059,
319105e, and 978eb1e before using the ZIP for another installation. Android
completed boot with the pvmfw fix; recovery was subsequently rebuilt and
verified with automatic touch and the banner margin, as recorded below.

## Flash sequence and failure diagnosis

| Stage | Observed result |
| --- | --- |
| First InfinityX physical flash | boot_a, init_boot_a, dtbo_a, vendor_boot_a, recovery_a, vbmeta_system_a, and vbmeta_a succeeded in bootloader |
| First fastbootd attempt | USB disappeared; logical images remained unflashed |
| Stock restore | User selected XT2601-2_ROADSTR_RETEU_16_W1WRS36.39-115-2_subsidy-DEFAULT_regulatory-DEFAULT_cid50_CFC; all 36 manifest MD5 hashes passed; flash.sh --serial ZY22M6NVR4 --keep-data --yes --reboot completed successfully |
| Second InfinityX physical flash | All seven images succeeded with slot A explicitly active |
| Empty super | fastboot wipe-super using canonical super_empty.img succeeded; fastbootd still failed with “No valid operating system” |
| Full super in bootloader | User authorized direct physical super flashing; all 27 sparse segments succeeded; the same boot error remained |
| Rollback check | fastboot oem read_sv reported stored RIL #0=1 and #2=1; InfinityX root index 1 met the stored floor |
| Temporary recovery boot | Image transfer succeeded; USB disconnected before command completion; the same error remained |
| Corrected vbmeta | Added the existing stock pvmfw descriptors, retaining the original descriptors, signing algorithm/key, flags 0, and root rollback index 1 |
| Verification | avbtool verify_image --follow_chain_partitions passed for the corrected root and complete image chain |
| Corrected vbmeta flash | vbmeta_a write succeeded in bootloader, slot A active |
| First corrected reboot | Reached fastbootd; is-userspace=yes, slot a, snapshot-update-status=none |
| Reboot from fastbootd | Android ADB came online and sys.boot_completed became 1 |

Flashing super_empty.img or a complete super.img alone did not resolve this
boot failure. Adding pvmfw authentication was the isolated change after which
fastbootd and Android started successfully. No verification-disable flags were
used. No userdata or metadata erase was performed.

The stock pvmfw image matches the already tracked prerequisite at
`device/motorola/roadstr/firmware/stock-W1WRS36.39-115-2/pvmfw.img`.
Its SHA-256 is `f26330e8865248c3296d2541af0717d90a160c57f57eae1f38eae0edbbe8b075`.
BoardConfig.mk now adds this image's descriptors to root vbmeta with
BOARD_AVB_MAKE_VBMETA_IMAGE_ARGS and --include_descriptors_from_image.

## Commits

All eight requested Lineage changes and shared framework/common companions
were committed individually. See the accompanying
`lineage-backport-audit-2026-10-15.md` for the complete backport table,
conflict decisions, and targeted verification history.

The remaining font and Launcher3 edits were committed following the explicit
request to commit all workspace changes:

| Repository | Commit | Change |
| --- | --- | --- |
| external/noto-fonts | 0c205c2307f7 | feat(fonts): retain custom CJK collections |
| external/roboto-fonts | 28ce103d080e | feat(fonts): retain custom Roboto replacements |
| frameworks/base | c544f5d48f5e | feat(fonts): retain framework font overrides |
| packages/apps/Launcher3 | d0735a1c0d4d | feat(launcher): allow default 4x5 grid overlay |

These pre-existing edits were preserved. XML, commented JSON configuration,
Python syntax, whitespace, and font container/table bounds were checked before
committing. No new full build was run solely for the bookkeeping commits.

## Evidence archive

A durable copy of this report, the full historical audit, flash/AVB logs,
image manifests, boot-verification JSON, and the small working vbmeta image
is saved under ~/Documents/. The evidence directory is
`RoadSTR-InfinityX-2026-10-15-evidence`.

This report and the historical audit are also committed under
`device/motorola/roadstr/docs/`. The workspace-root audits directory is outside
any Git repository, so its report is preserved through that tracked copy.


## Recovery touch and camera-cutout follow-up

The user reported that the recovery banner overlapped the front camera hole
and touch no longer worked. Both changes were implemented in device trees.

| Change | Repository | Commit |
| --- | --- | --- |
| Set TARGET_RECOVERY_UI_MARGIN_HEIGHT=10 | device/motorola/roadstr | 319105e |
| Start touch loader in recovery domain; remove duplicate init installation | device/motorola/sm7750-common | 978eb1e |

The banner uses ro.recovery.ui.margin_height directly as its top coordinate.
The 10-pixel setting shifts the banner/menu downward and reserves the same
vertical margin at the bottom. No recovery UI source change was needed.

The touch regression was an init service failure. The rootfs-labeled
/system/bin/recovery_touch_start script lacked an explicit SELinux domain,
so init rejected it before any touchscreen modules could load. The same init
file was also installed under both the root and system/etc/init paths,
causing duplicate service definitions. Adding seclabel u:r:recovery:s0 uses
the existing recovery domain/transition; retaining only the root init copy
removes the duplication. No SELinux policy relaxation was added.

Running the existing loader manually registered goodix_ts, and the user
confirmed touch worked. Then m -j8 recoveryimage passed in 8m34s. Inspection
of the built ramdisk confirmed exactly one recovery_touch service, its
explicit recovery domain, and ro.recovery.ui.margin_height=10.

A matching root vbmeta was generated from the already verified pvmfw-fixed
image with the new recovery descriptor. All other partition descriptors,
including pvmfw, remained unchanged. Flags 0 and root rollback index 1 were
retained; avbtool verify_image --follow_chain_partitions passed for the full
chain. recovery_a and vbmeta_a were flashed through bootloader with slot A
explicitly active, followed by fastboot reboot recovery. No userdata,
metadata, or logical partitions were written during this follow-up.

| Post-reboot check | Result |
| --- | --- |
| Recovery slot | _a |
| Margin property | 10 |
| Touch-loader service | Started automatically; exited status 0 after 4.197 seconds |
| Touchscreen | goodix_ts registered as input5/event5 at 6.446 seconds |
| Duplicate/transition errors | Absent |
| User confirmation | Touch works after reboot; banner placement accepted |

The following pair is the latest working recovery/vbmeta combination. The
initial pvmfw-only vbmeta above authenticates the previous recovery image;
it must not be paired with this newly rebuilt recovery.

| Current image | SHA-256 |
| --- | --- |
| /media/tony/SSD512/ProjectInfinityX/out/target/product/roadstr/recovery-touch-fixed.img | 1a78a6af7072860aedf82acef4fb471cda505ba48888351cf0130fe46bf58895 |
| /media/tony/SSD512/ProjectInfinityX/out/target/product/roadstr/vbmeta-recovery-touch-fixed.img | 15ef23e3c6660292fdda81b53a209c2b8ea1ed287fa5d1e41442b4851f1d56ae |

The phone is left in the successfully tested recovery. The original OTA ZIP
still predates these fixes and must be rebuilt before reuse.
