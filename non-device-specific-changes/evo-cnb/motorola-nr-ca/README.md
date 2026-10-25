# Motorola NR_CA registration compatibility

Supplement to the historical Evo cnb catalog, not a replacement for it.
The patches and manifest preserve the earlier NR_CA registration and 5G+
display snapshot. The shared implementation was subsequently committed as
`e0cdd3338ff29979c03607f41c46ac7adcc21ad8` in `frameworks/opt/telephony`
(`fix(telephony): recognize advanced NR signals`), including additional modem
icon-hint handling. This archived two-patch series does not include that later
extension and should not be reapplied over the committed implementation.

Motorola's AIDL radio reports private RAT30 for NR carrier aggregation.
Stock CellularNetworkService converts it to standard NR20 and preserves
`NetworkRegistrationInfo.isUsingCarrierAggregation=true`. Evo currently maps
it to UNKNOWN. A vendor binary table patch loses the aggregation distinction.
Normalize at the AIDL registration boundary instead, for both CS and PS.

No resource/property gate is needed: only the exact private value 30 triggers
the normalization path. This is a Motorola extension, not a new standard RAT
or proof that no other vendor could use the same private number. Other RATs
retain their existing conversion. HIDL conversion is unchanged.

The second patch makes PS/WWAN NR registration with CA=true qualify for the
existing NR_ADVANCED display path. It deliberately reads the data registration
flag, rather than ServiceState.isUsingCarrierAggregation(), which also includes
other domains, bandwidth heuristics and forced LTE+ settings. LTE CA/NSA does
not qualify via this new rule. Existing PCO, roaming, bandwidth, radio and
carrier icon-mapping restrictions remain in effect. No new public enum, flag,
resource gate or SystemUI change is introduced. NR CA is branded 5G+ here;
this does not establish 3GPP Release18/5G Advanced capability.

The shared change is needed to preserve the meaning supplied by the untouched
stock radio without adding a device adapter. Keep the original
`qcrilNr_aidl_IRadioNetwork.so`; remove the earlier extraction-table workaround.
Source restoration alone does not replace the radio already installed on a phone.

From the Android root, apply both patches in manifest order with
`git -C frameworks/opt/telephony apply <absolute-patch-path>` at the base in
series.json. These are working diffs, not mail patches. Verify the base/result tree
with an isolated Git index
as in the parent catalog. Roll back the patch as a unit. Deployment requires
the updated system telephony jar and a vendor image with the original blob.

Regression tests cover AIDL RAT30, standard NR20, LTE14, LTE_CA19 and unknown31
for both registration domains and both existing NR-NTN flag parameterizations.
Display tests cover NR CA gain/loss, LTE/NSA and aggregate ServiceState false
positives, and retained carrier restrictions. Compilation is not runtime proof.
Build/test status and stock evidence are recorded in
`documents/roadstr-stock-nr-ca-implementation.md` and
`documents/roadstr-nr-ca-5g-plus.md` in the Android checkout.

Attempt47 was subsequently built and flashed to system_a/vendor_a in fastbootd.
All 106 registration/display tests passed on the phone. Twelve live samples
showed NR+CA and NR_ADVANCED; the status bar displayed 5G+. Mobile data was
validated. Existing boot-time vendor denials/crashes remain; no new denial
signature was found against the saved attempt43 boot log. Detailed evidence,
initial transient ping loss and test limits are in the deployment report above.
