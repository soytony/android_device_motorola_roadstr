#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: 2025 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
from extract_utils.fixups_blob import blob_fixup
from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)

namespace_imports = [
    'vendor/motorola/sm7750-common',
    'hardware/motorola',
]

# The stock V2 interface hash differs from the source interface. Keep its ABI
# private to the stock fingerprint stack rather than replacing the source AIDL.
blob_fixups = {
    # Stock core-services wrappers reference device-local Motorola framework APIs.
    'system/etc/permissions/moto-core_services.xml': blob_fixup()
        .regex_replace(
            r'file="/system/framework/moto-core_services\.jar"(?:\s+dependency="moto-framework")?',
            'file="/system/framework/moto-core_services.jar"\n        dependency="moto-framework"',
        ),
    'vendor/bin/hw/vendor.dolby.media.c2-default-service-dax': blob_fixup()
        .add_needed('libcodec2_legacy_moto.so'),
    'vendor/bin/hw/android.hardware.biometrics.fingerprint-service-jv': blob_fixup()
        .replace_needed(
            'com.motorola.hardware.biometric.fingerprint-V2-ndk.so',
            'com.motorola.hardware.biometric.fingerprint-V2-ndk-moto.so',
        ),
}

module = ExtractUtilsModule(
    'roadstr',
    'motorola',
    blob_fixups=blob_fixups,
    namespace_imports=namespace_imports,
    add_generated_carriersettings_file=True,
)

if __name__ == '__main__':
    utils = ExtractUtils.device_with_common(
        module, 'sm7750-common', module.vendor
    )
    utils.run()
