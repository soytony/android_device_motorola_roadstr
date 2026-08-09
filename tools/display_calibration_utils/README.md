# Display Calibration Tools

These tools collect measurements for Contextual Ambient Lux Fusion. They do not
modify RRO resources or flash an image. Review generated CSV data before
changing `resource-overlay/roadstr/MotoRes/`.

## Requirements

- A userdebug/eng build, or an adb session allowed to change display brightness
  and read `dumpsys display` and `dumpsys sensorservice`.
- A stable adb connection. Pass an explicit serial whenever more than one device
  is connected.
- The Roadstr sensor stack published as
  `stk_stk3bfx Ambient Light Sensor Non-wakeup` for the front ALS and
  `stk_stk6b9x Alt Ambient Light Sensor Non-wakeup` for the rear ALS.
- A stable dark room for leakage and ROI measurements. Keep both sensor regions
  uncovered and keep external light off.

The Python scripts temporarily use manual brightness, extend screen timeout,
keep the device awake, and restore the saved settings in a `finally` block. Do
not interrupt adb or reboot the phone during a sweep unless recovery requires
it; check the relevant settings afterward if it does.

## Leakage Curve Collection

`calibrate_udals_leakage.py` measures the panel light observed by the front ALS
at a sequence of target nits. Its values are front-sensor-equivalent lux, not
physical panel transmission or display nits.

Before running it:

1. Put the device in a genuinely dark and stable environment.
2. Keep the front ALS uncovered and external light off.
3. Use a known static reference surface. A pure-white full-screen test surface
   is preferred for the ROI-white curve; a representative stable app surface is
   useful for a fallback curve.
4. Keep the rear ALS exposed so `rear_lux` can reveal environmental contamination.

```sh
python3 device/motorola/roadstr/tools/display_calibration_utils/calibrate_udals_leakage.py \
    --adb adb \
    --serial SERIAL \
    --theme light \
    --nits 7,18,36,65,130,257,497,669,703,740 \
    --settle-seconds 4 \
    --output /tmp/roadstr-light-leakage.csv
```

Use `--theme dark` for the dark-theme fallback curve. `--surface-command` can
launch a purpose-built reference activity before each sample; otherwise the
script opens Settings as a coarse fallback.

| Field | Meaning |
| --- | --- |
| `requested_nits` | Sweep point requested by the host |
| `brightness`, `backlight`, `target_nits` | Actual display values reconstructed from device splines |
| `front_lux`, `rear_lux` | Latest sensorservice lux channels from one dump |

Reject a run if rear lux changes materially, the front sensor is covered, or
read-back target nits do not track the requested sweep. Use settled `front_lux`
above the dark baseline to derive a curve aligned with
`config_motoAmbientLuxLeakageNits`. Do not apply a global multiplier to force a
result: excessive subtraction removes real room light and can oscillate.

## Front ALS ROI Scanner

The ROI scanner locates the front ALS optical footprint. It drives a fullscreen
black activity with a movable white square and records the front ALS response at
each square position. The result helps choose
`config_motoAmbientLuxRoiLeft/Top/Right/Bottom` and supports pure-white ROI
leakage calibration.

### Build and Install the Scanner APK

From a configured Roadstr build environment:

```sh
m FrontAlsRoiScanner
adb -s SERIAL install -r \
    out/target/product/roadstr/system/app/FrontAlsRoiScanner/FrontAlsRoiScanner.apk
```

The APK is a calibration helper only. It is not added to `PRODUCT_PACKAGES` and
must not be shipped in release images.

### Run a Scan

Run in a dark, stable room. Start with a coarse grid, then repeat with a denser
grid around the strongest `front_delta_lux` values.

```sh
python3 device/motorola/roadstr/tools/display_calibration_utils/scan_front_als_roi.py \
    --adb adb \
    --serial SERIAL \
    --brightness 0.65 \
    --size 160 \
    --x 0,180,360,540,720,900,1060 \
    --y 0,120,240,360,480,600 \
    --settle-seconds 4 \
    --output /tmp/roadstr-front-als-roi.csv
```

| Field | Meaning |
| --- | --- |
| `x`, `y`, `size` | Top-left pixel coordinate and square size in the portrait activity canvas |
| `front_lux` | Scanner activity's direct front ALS reading |
| `front_delta_lux` | Difference from the all-black baseline; maximize this to locate the footprint |
| `rear_lux` | Rear ALS control reading; a changing value indicates external-light contamination |

Select an ROI that covers the strongest response with a small margin, not the
whole top of the display. Re-run at multiple panel targets and verify that the
response location remains stable. The scanner fixes orientation to portrait, so
coordinates require transformation for another rotation.

## Runtime Diagnostics

While validating a newly built policy, enable concise processor logs:

```sh
adb -s SERIAL shell setprop persist.sys.moto.ambient_lux_debug 1
adb -s SERIAL logcat -s ContextualAmbientLuxProcessor
```

The log includes raw front lux, target nits, computed leakage, filtered front
and rear estimates, confidence, policy mode, and output lux. Disable it when
finished:

```sh
adb -s SERIAL shell setprop persist.sys.moto.ambient_lux_debug 0
```

For the algorithm and the generic framework patch, see
[`../../docs/contextual_ambient_lux_fusion/`](../../docs/contextual_ambient_lux_fusion/).
