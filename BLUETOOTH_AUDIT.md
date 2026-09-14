# roadstr Bluetooth audit — 2026-09-13

## Final playback fix — 2026-09-14

The user confirmed that `/sdcard/Music/08.mp3`, played in Lineage Twelve, is
properly audible through SRS-XB13 at reasonable volume with software A2DP.
Runtime evidence shows mIsPlaying=true, an active A2DP_SOFTWARE_ENCODING_DATAPATH,
and the media track on the dedicated `a2dp output` with the speaker's address.

Final configuration:

- Restore the classic Bluetooth profiles and phone class in common product props.
- Disable A2DP hardware offload: the source provider and stock QTI DSP bridge do
  not share session state. The observed QTI playback failure was ENODEV (-19),
  "BT device not connected or not ready", while Bluetooth remained connected.
- Register `libaudio_bluetooth_roadstr`, built against the current Module C++ ABI.
  Explicitly supply internal::getConfiguration(BLUETOOTH). The one-argument
  createInstance overload creates an empty non-null configuration, which leaves
  the service registered but with no usable audio ports.
- Remove the three A2DP output device ports/routes from roadstr's primary audio
  configuration so audio policy selects the software module. Retain SCO and
  unrelated input/LE routes.
- Select the source module from both generic and sku_sun interface XML, with
  idempotent extraction fixups. The running HAL selects the generic XML.
- Declare the source Bluetooth IModule at stable audio-core version 3 for bp4a.

Earlier validation uncovered a boot hang when a manifest declared a module that
was not registered, then an AudioPort parcel crash when the stock QTI Module
subclass was loaded against the source-built base class. The source module
resolves that C++ ABI mismatch; static symbol checks alone did not detect it.
Registration and boot success also did not establish a working playback route:
the empty configuration and incompatible offload path required the additional
playback fixes above.

The original bacon OTA predates these runtime corrections and must not be used.
The user requested partition images only. Images are flashed to explicitly active
slot A: physical ROM partitions through bootloader fastboot, logical partitions
through fastbootd. No agent-initiated data wipe was performed. The user wiped once
while diagnosing the earlier boot hang.

The verified live playback configuration was built and flashed successfully to
slot A. check-vintf-all passed. After cold boot, sys.boot_completed=1, offload is
disabled, all software Bluetooth ports are present, and the installed module and
primary configuration SHA-256 hashes match the build output. There are no
temporary test mounts. The first reconnect attempts received controller
PAGE_TIMEOUT from the speaker; audible playback after the flash awaits the
speaker becoming reachable again. Build log:
`/home/tony/Documents/build_logs/roadstr_bluetooth_software_playback_20260914.log`.
The findings below are retained as the original pre-fix audit.

Compared the connected Lineage device, current source, and mounted stock
XT2601-2 ROADSTR RETEU W1WRS36.39-115-2 firmware under
`/media/tony/TOSHIBA/Lenovo/MOTO_X70_Air/XT2601-2_ROADSTR_RETEU_16_W1WRS36.39-115-2_subsidy-DEFAULT_regulatory-DEFAULT_cid50_CFC/super_partition/mounted`.
Product and vendor Bluetooth properties match the saved reference files.
System-ext has one difference documented below. This is a configuration audit,
not certification of Bluetooth interoperability or successful audio playback.

## Confirmed missing profile configuration

Originally only GATT, HearingAid, and VAPS services started. The platform defaults
most other profiles to false when their properties are absent. The temporary
A2DP/AVRCP-target/HFP enablement starts those services successfully; the same three
properties are now patched into `device/motorola/sm7750-common/product.prop`.
No other settings were changed during this audit.

Stock enables these additional properties, absent on the running device:

| Property suffix (prefix `bluetooth.profile.`) | Missing functionality |
| --- | --- |
| `hid.host.enabled` | Bluetooth keyboards, mice, game controllers |
| `opp.enabled` | Bluetooth object/file transfer |
| `pan.nap.enabled`, `pan.panu.enabled` | Bluetooth network/tethering roles |
| `pbap.server.enabled` | Phonebook access from car kits |
| `map.server.enabled` | Message access from car kits |
| `bas.client.enabled` | GATT Battery Service client; other battery mechanisms are separate |
| `sap.server.enabled` | Remote SIM access; requires working SIM/RIL integration |
| `pbap.sim.enabled` | SIM phonebook support |
| `avrcp.controller.enabled` | AVRCP controller role; distinct from the target used for phone playback |

Stock also explicitly enables GATT and ASHA; those already run through platform
defaults. Stock explicitly disables HID-device mode. Do not enable A2DP-sink or
HFP-client merely to fix phone-to-speaker audio: those reverse the phone's role.

## High priority: incomplete A2DP audio path

The live stack reports `A2dpOffloadEnabled: false` and successfully opens
`A2DP_SOFTWARE_ENCODING_DATAPATH` through the AIDL Bluetooth audio provider.
However, live audio services expose only IModule/default, r_submix, and usb.
There is no IModule/bluetooth, and audio policy lists only three hardware modules.
The primary module advertises encoded Bluetooth output formats.

Stock declares IModule/bluetooth in
`vendor/etc/vintf/manifest/manifest_btaudiocoreservices_qti.xml`. That declaration
exists in the extracted vendor tree but is absent from the running image.
The roadstr primary-HAL integration patch explicitly removed registration of
`android.hardware.bluetooth.audio_sw.so` / `registerIModuleBluetoothSWQti`.
Installing the legacy `audio.bluetooth.default` module and HIDL policy XML does
not supply the missing AIDL IModule/bluetooth service.

Stock and the upstream sun audio configuration also enable:

```properties
ro.bluetooth.a2dp_offload.supported=true
persist.bluetooth.a2dp_offload.disabled=false
persist.bluetooth.a2dp_offload.cap=sbc-aac-aptx-aptxhd-ldac
persist.vendor.bt.a2dp_offload_cap=sbc-aptx-aptxtws-aptxhd-aac-ldac
vendor.audio.feature.a2dp_offload.enable=true
```

These are absent on the running device. The final property gates loading the
primary HAL's A2DP extension (default false); the read-only support property also
gates PAL/framework offload behavior. Existing `persist.vendor.btstack.*`
properties do not replace these gates. The controller reports an A2DP source
offload capability mask of 31, but that alone does not validate the full DSP path.

Recommendation: restore the stock offload property group together, then test
SBC/AAC playback and HFP. Separately restore and validate software-module
registration plus its manifest so disabling offload has a working fallback.
Do not restore only the manifest: a declaration cannot create a service.
These are strong configuration defects; a connection/playback trace is still
needed to establish their exact contribution after the profile fix.

## LE Audio and identity

Stock enables BAP unicast, broadcast source/assistant, VCP, CSIP, MCP, CCP and HAP,
plus LE Audio offload support. Those properties are absent and the corresponding
services are absent in the live dump. Restore as a coordinated feature group
only with LE Audio HAL/codec and compatible-accessory validation; these settings
are not required for the SRS-XB13's advertised classic A2DP service.

`bluetooth.device.class_of_device=90,2,12` is missing. The current native stack
falls back to an unclassified device when absent; restore the stock phone class.
Stock also sets `bluetooth.device_id.vendor_id=0x001D`; check the current stack's
Device ID configuration before copying identification overrides.

Mounted stock sets `bluetooth.hfp.swb.aptx.power_management.enabled=false`, while
`reference/system_ext_build.prop` says true. Use mounted stock for this setting.
Do not infer aptX Adaptive, aptX Voice, LE Audio, or proprietary Motorola feature
compatibility from the presence of vendor libraries alone.

## Checks without a demonstrated current blocker

- Bluetooth HCI, Bluetooth audio provider, Finder and SAR binder services exist.
  The missing audio-core Bluetooth module is a separate interface.
- Firmware is mounted read-only at `/vendor/bt_firmware`; logs show successful
  patch loading there, NVM fallback, and successful controller reset. Initial
  `/bt_firmware` file-open failures are followed by alternate-path success.
- GATT scanning and classic pairing work; Bluetooth manager reported zero crashes.
- SELinux is permissive. Logged denials therefore did not block this session.
  The vendor-init write to `persist.vendor.btstack.enable.splita2dp` targets a
  platform Bluetooth property label; resolve its ownership/necessity before an
  enforcing build rather than adding broad allow rules.

## Validation still needed

After a rebuild: verify final on-device properties and service registration;
connect SRS-XB13; confirm A2DP connected/active and actual audible playback;
test pause/resume, volume, reconnect and reboot persistence. Exercise a headset
call, HID device, file transfer, tethering and car contact/message access for
each restored profile. Test offload enabled and disabled independently. LE Audio
requires a compatible accessory. No full build or accessory playback test was
performed during this audit.

Session evidence is in `/tmp/roadstr-bt-debug/` (temporary, not committed), notably
`props.txt`, `bt-audit.txt`, `audio-policy.txt`, and `after.txt`.
