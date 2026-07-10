#!/usr/bin/env python3
"""Generate Roadstr runtime resource overlays from decompiled stock overlays.

Only resources present in the current Lineage target APK are copied.  This
keeps stock-only Motorola resource IDs out of the generated RROs.
"""

import argparse
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[4]
STOCK = Path("/home/tony/extracted_overlays")
AAPT2 = ROOT / "prebuilts/sdk/tools/linux/bin/aapt2"
ANDROID_NS = "http://schemas.android.com/apk/res/android"
# These framework settings point at proprietary Java implementations absent
# from Lineage and must not replace the platform defaults.
EXCLUDED_RESOURCES = {
    ("string", "config_deviceSpecificDevicePolicyManagerService"),
}

OVERLAYS = (
    # source, destination, target apk, module, partition, package, target, targetName, priority, property
    ("product_overlay/framework-res__roadstr_g__auto_generated_rro_product", "device/motorola/roadstr/overlay/FrameworkAutoProduct", "out/target/product/roadstr/system/framework/framework-res.apk", "FrameworkAutoProductRoadstr", "product", "android.auto_generated_rro_product_roadstr", "android", None, 1, None),
    ("product_overlay/MotoFrameworkResOverlayDualSim", "device/motorola/roadstr/overlay/MotoFrameworkDualSim", "out/target/product/roadstr/system/framework/framework-res.apk", "MotoFrameworkDualSimRoadstr", "product", "com.motorola.android.overlay.dualsim", "android", None, 2, ("ro.vendor.hw.dualsim", "true")),
    ("product_overlay/MotoFrameworkResOverlayQcomCommon", "device/motorola/roadstr/overlay/MotoFrameworkQcomCommon", "out/target/product/roadstr/system/framework/framework-res.apk", "MotoFrameworkQcomCommonRoadstr", "product", "com.motorola.android.overlay.qcom.common", "android", None, 7, None),
    ("product_overlay/MotoFrameworkResOverlayTrueAodIndia", "device/motorola/roadstr/overlay/MotoFrameworkTrueAodIndia", "out/target/product/roadstr/system/framework/framework-res.apk", "MotoFrameworkTrueAodIndiaRoadstr", "product", "com.motorola.android.overlay.true_aod.india", "android", None, 7, ("ro.carrier", "retin")),
    ("product_overlay/NetworkStackMcc460Overlay", "device/motorola/roadstr/overlay/NetworkStackMcc460", "out/target/product/roadstr/system/priv-app/NetworkStack/NetworkStack.apk", "NetworkStackMcc460Roadstr", "product", "com.motorola.android.networkstack.overlay.mcc460", "com.android.networkstack", "NetworkStackConfig", 500, None),
    ("product_overlay/NfcOverlay", "device/motorola/roadstr/overlay/Nfc", "out/target/product/roadstr/apex/com.android.nfcservices/priv-app/NfcNciApex@BP4A.251205.006/NfcNciApex.apk", "NfcRoadstr", "product", "com.android.nfc.overlay", "com.android.nfc", "NfcCustomization", 600, None),
    ("product_overlay/NfcStOverlay", "device/motorola/roadstr/overlay/NfcSt", "out/target/product/roadstr/apex/com.android.nfcservices/priv-app/NfcNciApex@BP4A.251205.006/NfcNciApex.apk", "NfcStRoadstr", "product", "com.android.nfc.overlay.StOverlay", "com.android.nfc", "NfcCustomization", 700, ("ro.vendor.hw.nfc.vendor", "st")),
    ("product_overlay/SystemUI__roadstr_g__auto_generated_rro_product", "device/motorola/roadstr/overlay/SystemUIAutoProduct", "out/target/product/roadstr/system_ext/priv-app/SystemUI/SystemUI.apk", "SystemUIAutoProductRoadstr", "product", "com.android.systemui.auto_generated_rro_product_roadstr", "com.android.systemui", None, 1, None),
    ("product_overlay/WifiResCommonOverlay", "device/motorola/roadstr/overlay/WifiCommon", "out/target/product/roadstr/apex/com.android.wifi/priv-app/ServiceWifiResources@BP4A.251205.006/ServiceWifiResources.apk", "WifiCommonRoadstr", "product", "com.android.wifi.resources.overlay.motCommon", "com.android.wifi.resources", "WifiCustomization", 600, None),
    ("vendor_overlay/framework-res__roadstr__auto_generated_rro_vendor", "device/motorola/sm7750-common/overlay/FrameworkAutoVendor", "out/target/product/roadstr/system/framework/framework-res.apk", "FrameworkAutoVendorRoadstr", "vendor", "android.auto_generated_rro_vendor_roadstr", "android", None, 0, None),
    ("vendor_overlay/MotoFrameworkResOverlayHWDualSim", "device/motorola/sm7750-common/overlay/MotoFrameworkHWDualSim", "out/target/product/roadstr/system/framework/framework-res.apk", "MotoFrameworkHWDualSimRoadstr", "vendor", "com.motorola.android.overlay.hw_dualsim", "android", None, 7, ("ro.vendor.hw.dualsim", "true")),
    ("vendor_overlay/MotoFrameworkResOverlayWFD", "device/motorola/sm7750-common/overlay/MotoFrameworkWFD", "out/target/product/roadstr/system/framework/framework-res.apk", "MotoFrameworkWFDRoadstr", "vendor", "com.motorola.android.overlay.wfd", "android", None, 6, None),
)


def target_resources(apk):
    output = subprocess.check_output((str(AAPT2), "dump", "resources", str(apk)), text=True)
    return set(re.findall(r"resource 0x[0-9a-f]+ ([^/\s]+)/([^\s]+)", output))


def value_identity(element):
    tag = element.tag.rsplit("}", 1)[-1]
    if tag == "public":
        return None
    resource_type = {"string-array": "array", "integer-array": "array"}.get(tag, tag)
    if tag == "item":
        resource_type = element.get("type")
    name = element.get("name")
    return (resource_type, name) if resource_type and name else None


def copy_resources(source, destination, resources):
    kept = skipped = 0
    for path in (source / "res").rglob("*"):
        if not path.is_file():
            continue
        rel = path.relative_to(source / "res")
        folder = rel.parts[0]
        output = destination / "res" / rel
        if folder.startswith("values") and path.suffix == ".xml":
            try:
                tree = ET.parse(path)
            except ET.ParseError as error:
                raise RuntimeError(f"Cannot parse {path}: {error}") from error
            root = tree.getroot()
            for element in list(root):
                identity = value_identity(element)
                if identity not in resources or identity in EXCLUDED_RESOURCES:
                    root.remove(element)
                    skipped += 1
                else:
                    kept += 1
            if not list(root):
                continue
            output.parent.mkdir(parents=True, exist_ok=True)
            tree.write(output, encoding="utf-8", xml_declaration=True)
            continue
        resource_type = folder.split("-", 1)[0]
        # Stock artwork and animation graphs are intentionally not ported.
        if resource_type in {"anim", "animator", "drawable", "interpolator", "mipmap", "transition"}:
            skipped += 1
            continue
        identity = (resource_type, path.stem)
        if identity not in resources:
            skipped += 1
            continue
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, output)
        kept += 1
    return kept, skipped


def write_module(destination, module, partition, package, target, target_name, priority, property_gate):
    destination.mkdir(parents=True, exist_ok=True)
    partition_flag = "product_specific" if partition == "product" else "vendor"
    (destination / "Android.bp").write_text(
        "// SPDX-License-Identifier: Apache-2.0\n\n"
        "runtime_resource_overlay {\n"
        f"    name: \"{module}\",\n"
        f"    {partition_flag}: true,\n"
        "}\n"
    )
    attrs = [f'android:targetPackage="{target}"', 'android:isStatic="true"', f'android:priority="{priority}"']
    if target_name:
        attrs.append(f'android:targetName="{target_name}"')
    if property_gate:
        attrs.extend((f'android:requiredSystemPropertyName="{property_gate[0]}"', f'android:requiredSystemPropertyValue="{property_gate[1]}"'))
    (destination / "AndroidManifest.xml").write_text(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        "<!-- SPDX-License-Identifier: Apache-2.0 -->\n"
        f"<manifest xmlns:android=\"{ANDROID_NS}\" package=\"{package}\">\n"
        f"    <overlay {' '.join(attrs)} />\n"
        "</manifest>\n"
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--clean", action="store_true", help="remove generated overlay directories before writing")
    args = parser.parse_args()
    for source_rel, destination_rel, apk_rel, module, partition, package, target, target_name, priority, property_gate in OVERLAYS:
        source, destination, apk = STOCK / source_rel, ROOT / destination_rel, ROOT / apk_rel
        if not apk.exists():
            raise FileNotFoundError(f"Target APK does not exist: {apk}")
        if args.clean and destination.exists():
            shutil.rmtree(destination)
        resources = target_resources(apk)
        write_module(destination, module, partition, package, target, target_name, priority, property_gate)
        kept, skipped = copy_resources(source, destination, resources)
        print(f"{module}: kept {kept}, skipped {skipped}")


if __name__ == "__main__":
    main()
