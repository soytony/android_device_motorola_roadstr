#!/usr/bin/env python3
"""Locate the front ALS optical footprint with a moving white rectangle.

The companion FrontAlsRoiScanner APK renders an otherwise black, fullscreen
surface and reports its direct front-ALS reading. This host script moves one
white rectangle through a coordinate grid, records the front-ALS delta from the
black baseline, and writes a CSV suitable for selecting a SurfaceFlinger ROI.

The script restores manual/automatic brightness mode, timeout, and stay-awake
state on exit. It is intended for a dark, stable room; see README.md.
"""

import argparse
import csv
import re
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

FRONT_SENSOR = "stk_stk3bfx Ambient Light Sensor Non-wakeup"
ACTIVITY = "com.motorola.tools.frontalsroiscanner/.ScannerActivity"
SCANNER_LUX_FILE = "/sdcard/Android/data/com.motorola.tools.frontalsroiscanner/files/front_lux.txt"


class Adb:
    """Small adb wrapper for one explicitly selected device."""

    def __init__(self, binary, serial):
        self.prefix = [binary, "-s", serial]

    def shell(self, command):
        """Run one device-shell command and return its trimmed stdout."""
        return subprocess.check_output(self.prefix + ["shell", command], text=True).strip()


def latest_lux(sensor_dump, name):
    """Read the latest first-channel value from a sensorservice event section."""
    section = re.search(re.escape(name) + r": last \d+ events\n(.*?)(?=\n\S|\Z)",
                        sensor_dump, re.DOTALL)
    if not section:
        return float("nan")
    values = re.findall(r"^\s*\d+ .*?\)\s+([-+]?\d+(?:\.\d+)?)\s*,", section.group(1),
                        re.MULTILINE)
    return float(values[-1]) if values else float("nan")


def stable_front_lux(adb):
    """Read the scanner's settled direct ALS sample.

    The scanner writes this file for the host after every callback. Reading it
    avoids a costly sensorservice dump for the front channel and prevents the
    Android launch splash from being mistaken for the test rectangle.
    """
    try:
        return float(adb.shell("cat " + SCANNER_LUX_FILE))
    except (ValueError, subprocess.CalledProcessError):
        return float("nan")


def start_pattern(adb, x=-1, y=-1, size=160):
    """Restart the scanner with a black surface and an optional white square.

    Negative coordinates request a fully black baseline. Restarting the activity
    clears the previous rectangle and keeps each grid measurement independent.
    """
    adb.shell("am force-stop com.motorola.tools.frontalsroiscanner")
    adb.shell("am start -n " + ACTIVITY + " --ei x " + str(x) + " --ei y " + str(y)
              + " --ei width " + str(size) + " --ei height " + str(size) + " >/dev/null")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--size", type=int, default=160)
    parser.add_argument("--brightness", type=float, default=0.65,
                        help="manual normalized panel brightness during the scan")
    parser.add_argument("--x", default="0,180,360,540,720,900,1060")
    parser.add_argument("--y", default="0,120,240,360,480,600")
    parser.add_argument("--settle-seconds", type=float, default=3.5)
    parser.add_argument("--baseline-settle-seconds", type=float, default=8.0,
                        help="extra dark-reference settling time after a panel level change")
    args = parser.parse_args()
    adb = Adb(args.adb, args.serial)
    old_mode = adb.shell("settings get system screen_brightness_mode")
    old_brightness = adb.shell("settings get system screen_brightness")
    old_timeout = adb.shell("settings get system screen_off_timeout")
    old_stayon = adb.shell("settings get global stay_on_while_plugged_in")
    try:
        xs = [int(value) for value in args.x.split(",")]
        ys = [int(value) for value in args.y.split(",")]
    except ValueError:
        parser.error("--x and --y must be comma-separated integer coordinates")
    if not xs or not ys or args.size <= 0:
        parser.error("--x and --y must be non-empty and --size must be positive")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        # Freeze the panel target so the front-lux delta is caused by rectangle
        # location rather than a simultaneous automatic-brightness adjustment.
        adb.shell("settings put system screen_brightness_mode 0")
        if not 0.0 <= args.brightness <= 1.0:
            parser.error("--brightness must be in the [0, 1] range")
        adb.shell("cmd display set-brightness " + str(args.brightness))
        adb.shell("settings put system screen_off_timeout 1800000")
        adb.shell("svc power stayon true")
        adb.shell("input keyevent KEYCODE_WAKEUP")
        start_pattern(adb)
        time.sleep(max(args.settle_seconds, args.baseline_settle_seconds))
        # The all-black scanner surface establishes the front ALS's panel-leakage baseline.
        baseline = stable_front_lux(adb)
        print(f"dark baseline front_lux={baseline:.3f}", flush=True)
        with args.output.open("w", newline="") as file:
            writer = csv.DictWriter(file, fieldnames=("utc", "x", "y", "size", "front_lux",
                                                       "front_delta_lux", "rear_lux"))
            writer.writeheader()
            for y in ys:
                for x in xs:
                    start_pattern(adb, x, y, args.size)
                    time.sleep(args.settle_seconds)
                    front = stable_front_lux(adb)
                    # Retain rear lux as an environmental control in the CSV. A material
                    # change warns that room illumination contaminated this grid point.
                    sensors = adb.shell("dumpsys sensorservice")
                    rear = latest_lux(sensors, "stk_stk6b9x Alt Ambient Light Sensor Non-wakeup")
                    row = {"utc": datetime.now(timezone.utc).isoformat(), "x": x, "y": y,
                           "size": args.size, "front_lux": f"{front:.3f}",
                           "front_delta_lux": f"{front - baseline:.3f}",
                           "rear_lux": f"{rear:.3f}"}
                    writer.writerow(row)
                    print(", ".join(f"{key}={value}" for key, value in row.items()), flush=True)
    finally:
        start_pattern(adb)
        adb.shell("settings put system screen_brightness_mode " + old_mode)
        adb.shell("settings put system screen_brightness " + old_brightness)
        adb.shell("settings put system screen_off_timeout " + old_timeout)
        adb.shell("settings put global stay_on_while_plugged_in " + old_stayon)


if __name__ == "__main__":
    main()
