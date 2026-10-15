# Baidu network location: CN hardware selection and validation

## Changes

The framework overlay now requires `ro.boot.hardware.sku=XT2601-1`.
Its provider resources use ordinary `values/`, so selection is available
during framework startup without waiting for a SIM MCC. Other hardware SKUs
retain the existing default network-provider configuration. The legacy
`NetworkLocation460` module/package name is retained for compatibility;
it no longer uses an MCC resource qualifier.

Product default permissions grant `com.baidu.map.location` coarse, fine,
background location, and `WRITE_EXTERNAL_STORAGE`. The stock APK explicitly
checks the legacy storage permission before starting positioning or
geocoding; without it, the provider logs `reason:[no permission]` even when
all three location permissions are granted. The APK and its signature are
unchanged. Android also granted the related legacy read-storage permission
through its permission handling.

## Device results

Test device: ZY22M6NVR4, hardware SKU `XT2601-1`, boot carrier `retin`.
This is CN hardware with cross-flashed firmware, so carrier-based gating
would select the wrong region.

- Android completed boot after flashing through fastbootd on slot A.
- Framework resources selected Baidu from the overlay's default configuration.
- The bound network service was
  `com.baidu.map.location/.BaiduNetworkLocationService@10`, connected and enabled.
- All four configured permissions were granted with `GRANTED_BY_DEFAULT`.
  The temporary diagnostic storage grant was revoked before the final flash,
  so the final result verifies the product permission XML.
- The default data network was validated cellular NR, with no VPN transport.
- A temporary app requested only Android's `network` provider. On the final
  boot its request started at 23:20:02.626 and received a non-mock fix at
  23:20:03.067, reporting 50.1 m horizontal accuracy and a location age of 9 ms.
- Baidu logged `locType=161`, `NetworkLocationType = wf`: Wi-Fi observations
  supplied positioning data while cellular supplied the Internet connection.
- An earlier diagnostic run returned six network fixes during a 60-second
  request after the missing storage permission was identified.

This validates network positioning on this CN device. Other hardware SKUs,
Google fused-location clients, reverse geocoding, and absolute coordinate
accuracy were not independently validated by this test.

## Build and flash

Build: source `build/envsetup.sh`, lunch `infinity_roadstr-userdebug`, then
`m -j8 productimage`. The final build passed in 3m04s; the initial build with
the overlay changes passed in 11m17s. XML parsing and `git diff --check` passed.
The compiled overlay contains the exact hardware-property requirement and
has no MCC-qualified provider resources.

The installed `vbmeta_system_a` and root `vbmeta_a` were backed up through
recovery ADB before entering fastbootd. Local top-level build metadata differed
from the installed metadata, so a matching `vbmeta_system` was signed using
the installed descriptors with only the product descriptors replaced.
System and system_ext descriptors were preserved byte for byte. The signing
key matches the installed root chain; rollback index 1785542400 and flags 0
were retained. AVB signature and all three partition hashtrees verified.

Final artifacts:

| Image | SHA-256 |
| --- | --- |
| `out/target/product/roadstr/product.img` | `dc5026e08b9e187820fdc17dbbe408afd512547ae370eeea612bc1f64ead42c0` |
| `out/target/product/roadstr/vbmeta_system-baidu-location.img` | `f71cf7e17fce9edf6b40ff1d11acfc14311c204e077832b393275d8d1cf68ac4` |

With fastbootd reporting `is-userspace=yes`, `current-slot=a`, and no pending
snapshots, the final flash commands were:

```sh
fastboot -s ZY22M6NVR4 flash product_a out/target/product/roadstr/product.img
fastboot -s ZY22M6NVR4 flash vbmeta_system_a out/target/product/roadstr/vbmeta_system-baidu-location.img
fastboot -s ZY22M6NVR4 reboot
```

Both writes succeeded. Fastboot resized the logical product partition to fit
the image. Root vbmeta, other partition contents, userdata, and metadata were
not flashed or erased. The temporary probe app was removed after testing.

Build/flash logs, permission/provider dumps, probe source/results, AVB backups,
and the metadata-generation script are archived in
`/home/tony/Documents/RoadSTR-BaiduLocation-2026-10-15/`.
The metadata-generation script expects the workspace and the original
`/tmp/roadstr-baidu-evidence/` paths; adjust paths before reuse.
