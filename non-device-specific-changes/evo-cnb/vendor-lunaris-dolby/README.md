# Lunaris Dolby privapp-permissions module

Supplement to the historical Evo cnb catalog, not a replacement for it.
`vendor/lunaris/dolby` is vendored outside repo manifest tracking, so its
local delta is recorded here as a working diff rather than a shared commit.

Upstream `vendor_lunaris_dolby` ships
`configs/permissions/privapp-permissions-dolby.xml` but never installs it.
RoadSTR's `device/motorola/roadstr/lunaris-dolby.mk` selects
`LunarisDolbyPrivappPermissions` in `PRODUCT_PACKAGES`, so the shared
`Android.bp` gains a `prebuilt_etc` module that installs the stock XML to
`system_ext/etc/permissions`. The XML content itself is unmodified upstream;
only the Soong module is added.

The whitelist is required for the LunarisDolby priv-app to keep its Dolby
permissions on a userdebug build. Other devices that include the same
`vendor/lunaris/dolby` checkout and reference the module get identical
behavior; the module is inert unless a product requests it.

From the Android root, apply with
`git -C vendor/lunaris/dolby apply <absolute-patch-path>` at the base in
series.json. This is a working diff, not a mail patch. Verify the base and
result tree with an isolated Git index as in the parent catalog. Roll back
the patch as a unit if the dolby vendor tree is re-synced.

Preserve the RoadSTR adaptation: LunarisDolby UI/resources plus this
permission metadata, paired with the stock Motorola 64-bit Dolby HALs from
the roadstr vendor tree. The upstream generic Xiaomi SM6375 ODM blobs are
not a replacement for this stack.
