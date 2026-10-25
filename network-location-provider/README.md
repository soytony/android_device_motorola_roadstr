# Network location provider

RoadSTR port of Annibale's boot-pinned broker and provider switcher. Google is
the initial provider on every SKU; Baidu NLP is the alternative. Confirmed
changes take effect after reboot. Country detection only suggests a change.

The broker and switcher are platform signed. The broker uses system UID to
bind Google's non-exported geocoder and protects its exported endpoint with a
signature permission. Both Baidu bind actions target the stock
`com.baidu.map.location.BaiduNetworkLocationService`. Keep the stock APK and
Baidu location/storage permission grants.

The product RRO configures both network location and geocoding through the
broker. Do not restore the old fixed Baidu or Qualcomm framework overrides.
The local Soong namespace keeps this variant separate from other device trees.
The integration test APK is a manual build target, not an installed product app.

Build/signing notes and validation results:
`documents/roadstr-nlp-broker-20261025/README.md` in the source checkout.
