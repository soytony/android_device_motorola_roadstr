# Roadstr automatic-brightness integration

This directory contains the release-facing design and integration material for
Roadstr's stock Motorola automatic-brightness compensation path. The former
ROI/rear-sensor contextual implementation is retained only as historical
material; it is not packaged or selected by current builds.

| Document | Purpose |
| --- | --- |
| [Design document](design.md) | Active workflow, device-tree structure, resources, validation, and historical context |
| [Framework patch guide](frameworks_base_patch.md) | Generic `frameworks/base` interface and integration snippets required by the policy |

The device-specific implementation is intentionally kept in this device tree:
`moto-framework.jar` contains only the `ISensorExt` bridge, while `moto-res.apk`
plus its Roadstr RRO supplies the sensor identifier and vendor configuration
path. Stock panel calibration, TFLite models, and the native filter remain in
the vendor partition. `FrameworksResRoadstr` contains only the bridge class
name; it does not carry pipe-delimited parameters.

Runtime flow:

`AutomaticBrightnessController` → `StockAlsCompensationProcessor` →
`motorola.hardware.sensors.ISensorExt/default` → stock vendor ALS compensation
→ ordinary AOSP lux filtering and brightness mapping.
