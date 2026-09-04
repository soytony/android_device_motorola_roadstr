# RoadSTR stock firmware prerequisites

These files are copied byte-for-byte from the Motorola XT2601-2 RETEU CFC
package:

`XT2601-2_ROADSTR_RETEU_16_W1WRS36.39-115-2_subsidy-DEFAULT_regulatory-DEFAULT_cid50_CFC`

They are release firmware prerequisites, not Android filesystem inputs. A
release installer may flash them before the LineageOS images when upgrading
from an incompatible firmware baseline.

Included partitions:

- `gpt.bin` → `partition`
- `bootloader.img` → `bootloader`
- `radio.img` → `radio`
- `BTFM.bin` → `bluetooth`
- `dspso.bin` → `dsp`
- `pvmfw.img` → `pvmfw`

The stock `super.img` and stock `logo.bin` are intentionally not included.
LineageOS supplies its own dynamic-partition images, and this tree retains the
custom logo at `../prebuilt/logo.img`.

Verify the payloads with `SHA256SUMS` before flashing. `gpt.bin` and
`bootloader.img` modify low-level boot-chain state; do not downgrade or mix
firmware from another model, region, or software branch.
