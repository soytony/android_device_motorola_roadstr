# Contextual Ambient Lux Fusion

This directory contains the release-facing design and integration material for
Roadstr's custom automatic-brightness policy.

| Document | Purpose |
| --- | --- |
| [Design document](design.md) | Device architecture, algorithm, resources, calibration, validation, and limits |
| [Framework patch guide](frameworks_base_patch.md) | Generic `frameworks/base` interface and integration snippets required by the policy |

The device-specific implementation is intentionally kept in this device tree:
`moto-framework.jar` implements the policy and `moto-res.apk` plus its Roadstr
RRO supply every sensor-specific identifier, calibration curve, and threshold.
The framework changes described here deliberately contain none of those values.
