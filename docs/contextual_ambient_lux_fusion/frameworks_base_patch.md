# Generic `frameworks/base` Patch Guide

## Scope

The device policy requires a small extension point in `frameworks/base`. The
extension is deliberately generic: it does not name a device, vendor, sensor,
resource package, calibration table, or fusion algorithm. Those remain in the
device tree.

Apply the following commits in order when building a branch that does not yet
contain the hook:

```text
9a9caba6fa2492e3d2c75d622d8a7ddffb3395ce feat(display): add ambient lux processor hook
e9fdc7201c4d6622ee6439916de5833e45a1e3f9 feat(display): add target-aware lux hook
00b4a8653d0c745a05fdbdb37143e0c3afe9eedf fix(display): retain applied nits for lux hooks
```

The following excerpts document the final interface and integration shape for
manual porting across framework revisions.

## 1. Define the Generic Interface

Add `core/java/android/hardware/display/AmbientLuxProcessor.java`:

```java
/**
 * Optional in-process device-specific ambient-lux processor.
 *
 * @hide
 */
public interface AmbientLuxProcessor {
    void initialize(Context context, SensorManager sensorManager, Handler handler,
            String[] parameters);
    void start();
    void stop();

    void onTargetNitsChanged(long timestampMillis, float targetNits);

    /** Returns processed lux, or Float.NaN to keep the primary ALS sample. */
    float process(long timestampMillis, float primaryLux, boolean dozing);
    void reset();
}
```

The primary sample is passed synchronously. A device implementation must not
register a second listener for the primary ALS because listener callback order
is not guaranteed.

## 2. Add an Empty Framework Resource

In `core/res/res/values/config.xml` add an empty string, and expose it from
`core/res/res/values/symbols.xml`:

```xml
<!-- Optional in-process device-specific ambient-lux processor. -->
<string name="config_ambientLuxProcessorClass" translatable="false" />
```

```xml
<java-symbol type="string" name="config_ambientLuxProcessorClass" />
```

An enabled device RRO supplies only the implementation class name, for example:

```xml
<string name="config_ambientLuxProcessorClass" translatable="false">
    com.example.display.CustomAmbientLuxProcessor
</string>
```

The optional `|` plus comma-separated suffix is retained as opaque parameters
for the device implementation. The framework must not parse vendor policy.

## 3. Discover the Provider Safely

In `AutomaticBrightnessController`, load the configured class once using the
controller class loader. Verify its type, initialise it with the existing
`Context`, `SensorManager`, and handler, and return `null` on any failure:

```java
@Nullable
private AmbientLuxProcessor createAmbientLuxProcessor() {
    final String declaration = mContext.getResources().getString(
            R.string.config_ambientLuxProcessorClass);
    if (declaration == null || declaration.isEmpty()) return null;

    final String[] parts = declaration.split("\\|", 2);
    final String className = parts[0].trim();
    final String[] parameters = parts.length == 2 && !parts[1].isEmpty()
            ? parts[1].split(",") : new String[0];
    try {
        final Object instance = Class.forName(className, true,
                AutomaticBrightnessController.class.getClassLoader())
                .getDeclaredConstructor().newInstance();
        if (!(instance instanceof AmbientLuxProcessor)) {
            throw new IllegalArgumentException("does not implement AmbientLuxProcessor");
        }
        final AmbientLuxProcessor processor = (AmbientLuxProcessor) instance;
        processor.initialize(mContext, mSensorManager, mHandler, parameters);
        return processor;
    } catch (Throwable error) {
        Slog.w(TAG, "Unable to load ambient-lux processor " + className, error);
        return null;
    }
}
```

Start it when automatic brightness enables and stop it when the primary ALS
listener is disabled. On any processing failure, stop and null only the optional
provider; do not disable ordinary automatic brightness.

## 4. Send the Applied Panel Target

The processor needs the target already applied when a later front ALS sample
was measured. Keep the last valid configured brightness, because automatic
brightness may be enabled while the panel is still at a manual target:

```java
private float mLastConfiguredBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;

// At the beginning of configure(...):
if (BrightnessUtils.isValidBrightnessValue(brightness)) {
    mLastConfiguredBrightness = brightness;
}

private float getCurrentTargetNits() {
    final float brightness = BrightnessUtils.isValidBrightnessValue(mScreenAutoBrightness)
            ? mScreenAutoBrightness : mLastConfiguredBrightness;
    return BrightnessUtils.isValidBrightnessValue(brightness)
            ? mCurrentBrightnessMapper.convertToNits(brightness) : Float.NaN;
}
```

Notify the provider after committing a new accepted automatic target, when the
provider starts, and immediately before each processor call. The last action is
important because normal framework hysteresis can hold a target for a long time:

```java
private void notifyAmbientLuxProcessorTargetChanged() {
    if (mAmbientLuxProcessor == null) return;
    final float targetNits = getCurrentTargetNits();
    if (!Float.isFinite(targetNits) || targetNits < 0) return;
    try {
        mAmbientLuxProcessor.onTargetNitsChanged(
                mClock.getSensorEventScaleTime(), targetNits);
    } catch (Throwable error) {
        disableAmbientLuxProcessor(error);
    }
}
```

## 5. Process the Primary Sample Before Existing Filtering

At the start of `handleLightSensorEvent`, before the existing ambient-light
ring buffer receives the measurement:

```java
if (mAmbientLuxProcessor != null) {
    try {
        notifyAmbientLuxProcessorTargetChanged();
        final float processedLux = mAmbientLuxProcessor.process(time, lux,
                mCurrentBrightnessMapper.getMode() == AUTO_BRIGHTNESS_MODE_DOZE);
        if (Float.isFinite(processedLux) && processedLux >= 0) {
            lux = processedLux;
        }
    } catch (Throwable error) {
        disableAmbientLuxProcessor(error);
    }
}
```

The remainder of `handleLightSensorEvent` stays unchanged. It continues to
apply standard ring buffering, debounce, hysteresis and brightness mapping to
the returned lux.

## Compatibility Notes

- Keep the interface disabled by default with an empty framework resource.
- Treat the class declaration and optional parameters as opaque; device
  resources belong outside `framework-res`.
- Catch `Throwable` at the isolated provider boundary. A bad device JAR must
  degrade to primary-only automatic brightness, not break `system_server`.
- Port the integration by method semantics rather than line numbers: Android
  releases often move `AutomaticBrightnessController` details.
- The implementation must be loadable by the `system_server` class loader;
  install the device JAR in the corresponding system-server JAR list.
