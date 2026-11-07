# Motorola Camera icon overlay

`MotoCameraIconRoadstr` is a static product RRO targeting `com.motorola.camera5`.
Its explicit resource map replaces only `mipmap/ic_launcher_camera` and
`mipmap/ic_launcher_camera_round` with the same adaptive icon. Foreground,
background and monochrome layers are included, so launcher masks and themed
icons remain supported. Camera activities, intents and permissions are unchanged.

The three 432x432 PNGs and adaptive-icon references were supplied from
`/tmp/opencode/pixel-camera-decoded/res/`. PNG bytes are copied unchanged from
its `mipmap-xxxhdpi` directory; the XML comes from
`mipmap-anydpi/adaptiveproduct_pixelcamera.xml`. These are extracted Pixel Camera
art assets, not newly authored artwork; preserve their provenance.

Local resources use `ic_camera.xml`, `ic_camera_foreground.png`,
`ic_camera_background.png` and `ic_camera_monochrome.png`; renaming does not
change the supplied PNG bytes.

The overlay must be preinstalled on product for target overlay policy checks.
Source checks are not an idmap/runtime validation. Build, idmap generation and
launcher/themed-icon verification are required before claiming deployment.
