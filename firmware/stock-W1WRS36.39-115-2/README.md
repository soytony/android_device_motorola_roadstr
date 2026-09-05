# RoadSTR stock firmware prerequisites

These files are copied byte-for-byte from the Motorola XT2601-2 RETEU CFC
package:

`XT2601-2_ROADSTR_RETEU_16_W1WRS36.39-115-2_subsidy-DEFAULT_regulatory-DEFAULT_cid50_CFC`

They are release firmware prerequisites, not Android filesystem inputs. The
device tree registers them as custom image payloads, so they are carried into
target-files and release image packages. A release installer may flash them
before the LineageOS images when upgrading from an incompatible firmware
baseline.

Included partitions:

- `logo.img` → `logo` (LineageOS replacement)
- `partition.img` → `partition`
- `bootloader.img` → `bootloader`
- `radio.img` → `radio`
- `bluetooth.img` → `bluetooth`
- `dsp.img` → `dsp`
- `pvmfw.img` → `pvmfw`

The stock `super.img` and stock `logo.bin` are intentionally not included.
LineageOS supplies its own dynamic-partition images and the replacement
`logo.img` stored alongside these prerequisite images.

Verify the payloads with `SHA256SUMS` before flashing. `partition.img` and
`bootloader.img` modify low-level boot-chain state; do not downgrade or mix
firmware from another model, region, or software branch.
