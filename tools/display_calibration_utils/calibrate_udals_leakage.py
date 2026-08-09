#!/usr/bin/env python3
"""Collect dark-room front-ALS panel-leakage measurements for calibration.

The script disables automatic brightness, traverses requested panel targets in
nits, and writes the front and rear ALS readings together with the effective
brightness, backlight, and target nits to CSV. It restores the modified display
settings in ``finally`` even when a sweep fails.

This is a measurement tool, not an RRO editor. Review the CSV before changing a
leakage table: a covered sensor, external light, or changing reference surface
will contaminate the result. See README.md for required device state and
the calibration workflow.
"""

import argparse
import csv
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path


FRONT_SENSOR = "stk_stk3bfx Ambient Light Sensor Non-wakeup"
REAR_SENSOR = "stk_stk6b9x Alt Ambient Light Sensor Non-wakeup"


class Adb:
    """Minimal wrapper that keeps the selected adb binary and serial consistent."""

    def __init__(self, adb_path, serial):
        self.prefix = [adb_path] + (["-s", serial] if serial else [])

    def shell(self, command):
        """Run one device-shell command and return its trimmed stdout."""
        return subprocess.check_output(self.prefix + ["shell", command], text=True).strip()


def sensor_lux(sensor_dump, sensor_name):
    """Return the newest first-channel value from one sensorservice event section.

    Sensorservice reports each event as a timestamp followed by comma-separated
    values. Both Roadstr ALS devices publish calibrated lux in channel zero.
    Missing events deliberately yield NaN so the CSV preserves the failed sample
    rather than inventing a zero-lux reading.
    """
    section = re.search(re.escape(sensor_name) + r": last \d+ events\n(.*?)(?=\n\S|\Z)",
                        sensor_dump, re.DOTALL)
    if not section:
        return float("nan")
    events = re.findall(r"^\s*\d+ .*?\)\s+([-+]?\d+(?:\.\d+)?)\s*,", section.group(1),
                        re.MULTILINE)
    return float(events[-1]) if events else float("nan")


def spline_pairs(display_dump, name):
    """Extract one LinearSpline from ``dumpsys display`` as numeric pairs."""
    match = re.search(re.escape(name) + r"=LinearSpline\{\[(.*?)\]\}", display_dump)
    if not match:
        raise RuntimeError("Unable to locate " + name + " in dumpsys display")
    return [(float(x), float(y)) for x, y in re.findall(
        r"\(([-+0-9.Ee]+),\s*([-+0-9.Ee]+)", match.group(1))]


def interpolate(value, pairs):
    """Linearly interpolate a monotonic spline, clamping outside its endpoints."""
    if value <= pairs[0][0]:
        return pairs[0][1]
    for (x0, y0), (x1, y1) in zip(pairs, pairs[1:]):
        if value <= x1:
            if x1 == x0:
                return y1
            return y0 + (value - x0) * (y1 - y0) / (x1 - x0)
    return pairs[-1][1]


def current_brightness_and_nits(adb):
    """Read current normalized brightness, hardware backlight, and target nits."""
    display = adb.shell("dumpsys display")
    brightness_match = re.search(r"(?m)^\s*mScreenBrightness=([-+0-9.Ee]+)", display)
    if not brightness_match:
        raise RuntimeError("Unable to locate current screen brightness")
    brightness = float(brightness_match.group(1))
    brightness_to_backlight = spline_pairs(display, "mBrightnessToBacklightSpline")
    nits_to_backlight = spline_pairs(display, "mNitsToBacklightSpline")
    backlight = interpolate(brightness, brightness_to_backlight)
    # Invert the monotonic nits-to-backlight spline by swapping its axes.
    nits = interpolate(backlight, [(backlight, nits) for nits, backlight in nits_to_backlight])
    return brightness, backlight, nits


def brightness_for_nits(adb, target_nits):
    """Invert the device display splines for a reproducible manual target."""
    display = adb.shell("dumpsys display")
    brightness_to_backlight = spline_pairs(display, "mBrightnessToBacklightSpline")
    nits_to_backlight = spline_pairs(display, "mNitsToBacklightSpline")
    backlight = interpolate(target_nits, nits_to_backlight)
    return interpolate(backlight, [(y, x) for x, y in brightness_to_backlight])


def set_and_verify_brightness(adb, target_brightness, timeout_seconds=5.0):
    """Apply a manual target and reject a sample unless DisplayPowerState reached it."""
    adb.shell("cmd display set-brightness " + str(target_brightness))
    deadline = time.monotonic() + timeout_seconds
    while True:
        brightness, _, _ = current_brightness_and_nits(adb)
        # The display command and dump use the same normalized brightness domain. A small
        # tolerance covers panel ramp timing without accepting a screen-off/minimum readback.
        if abs(brightness - target_brightness) <= 0.002:
            return
        if time.monotonic() >= deadline:
            raise RuntimeError("Display did not reach requested brightness "
                               + f"{target_brightness:.6f}; read back {brightness:.6f}")
        time.sleep(0.25)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="adb serial; uses the sole connected device by default")
    parser.add_argument("--adb", default="adb", help="adb binary to use")
    parser.add_argument("--theme", choices=("light", "dark"), required=True)
    parser.add_argument("--nits", default="7,18,36,65,130,257,497,669,703,740",
                        help="comma-separated display targets in nits")
    parser.add_argument("--settle-seconds", type=float, default=3.0,
                        help="panel and ALS settle time at each level")
    parser.add_argument("--surface-command",
                        help="optional device-shell command that opens a stable reference "
                             "surface before each sample; defaults to Settings")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        target_nits = [float(value) for value in args.nits.split(",")]
    except ValueError:
        parser.error("--nits must be a comma-separated list of numbers")
    if not target_nits or any(value <= 0 for value in target_nits):
        parser.error("--nits must contain positive values")

    adb = Adb(args.adb, args.serial)
    # Capture every changed global/system setting before touching the device.
    # ``finally`` below restores this state even if a sensor or display dump fails.
    old_mode = adb.shell("settings get system screen_brightness_mode")
    old_brightness = adb.shell("settings get system screen_brightness")
    old_adjustment = adb.shell("settings get system screen_auto_brightness_adj")
    old_stayon = adb.shell("settings get global stay_on_while_plugged_in")
    old_timeout = adb.shell("settings get system screen_off_timeout")
    old_night_mode = adb.shell("cmd uimode night")
    if "not" not in old_night_mode.lower() and "yes" not in old_night_mode.lower() \
            and "no" not in old_night_mode.lower():
        raise RuntimeError("Unable to determine current night mode: " + old_night_mode)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        # Manual mode prevents the policy under test from changing the panel during a sweep.
        adb.shell("settings put system screen_brightness_mode 0")
        # Avoid the dim timeout without tapping arbitrary coordinates in the reference surface.
        adb.shell("settings put system screen_off_timeout 1800000")
        adb.shell("cmd uimode night " + ("yes" if args.theme == "dark" else "no"))
        # Settings is only a coarse fallback: its composition can change with scroll state.
        # A purpose-built white or black reference activity is preferred for calibration.
        adb.shell(args.surface_command or "am start -a android.settings.SETTINGS >/dev/null")
        adb.shell("svc power stayon true")
        adb.shell("input keyevent KEYCODE_WAKEUP")
        time.sleep(max(args.settle_seconds, 2.0))

        with args.output.open("w", newline="") as output:
            writer = csv.DictWriter(output, fieldnames=(
                "utc", "theme", "requested_nits", "brightness", "backlight", "target_nits",
                "front_lux", "rear_lux"))
            writer.writeheader()
            for requested_nits in target_nits:
                if args.surface_command:
                    adb.shell(args.surface_command)
                # Invert the panel splines and use the default float interface. The device's
                # nits-unit shell path clamps values unexpectedly, so it cannot drive a sweep.
                target_brightness = brightness_for_nits(adb, requested_nits)
                set_and_verify_brightness(adb, target_brightness)
                time.sleep(args.settle_seconds)
                brightness, backlight, nits = current_brightness_and_nits(adb)
                # One sensorservice dump keeps front/rear values from the same observation
                # window. The requested and read-back nits expose display-spline error.
                sensors = adb.shell("dumpsys sensorservice")
                row = {
                    "utc": datetime.now(timezone.utc).isoformat(),
                    "theme": args.theme,
                    "requested_nits": f"{requested_nits:.3f}",
                    "brightness": f"{brightness:.6f}",
                    "backlight": f"{backlight:.6f}",
                    "target_nits": f"{nits:.3f}",
                    "front_lux": f"{sensor_lux(sensors, FRONT_SENSOR):.3f}",
                    "rear_lux": f"{sensor_lux(sensors, REAR_SENSOR):.3f}",
                }
                writer.writerow(row)
                print(", ".join(f"{key}={value}" for key, value in row.items()), flush=True)
    finally:
        adb.shell("settings put system screen_brightness_mode " + old_mode)
        adb.shell("settings put system screen_brightness " + old_brightness)
        adb.shell("settings put system screen_auto_brightness_adj " + old_adjustment)
        adb.shell("settings put global stay_on_while_plugged_in " + old_stayon)
        adb.shell("settings put system screen_off_timeout " + old_timeout)
        if "yes" in old_night_mode.lower():
            adb.shell("cmd uimode night yes")
        elif "no" in old_night_mode.lower():
            adb.shell("cmd uimode night no")


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError) as error:
        print("calibration failed: " + str(error), file=sys.stderr)
        sys.exit(1)
