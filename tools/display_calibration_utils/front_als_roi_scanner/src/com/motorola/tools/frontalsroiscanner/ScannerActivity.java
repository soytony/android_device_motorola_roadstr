package com.motorola.tools.frontalsroiscanner;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Draws a coordinate-stable dark test surface with one controllable bright rectangle.
 *
 * <p>The host scanner starts this activity repeatedly with pixel coordinates. The fullscreen,
 * black canvas limits panel leakage to the requested rectangle, while the direct ALS callback is
 * published to app-specific external storage for adb-shell collection.</p>
 */
public final class ScannerActivity extends Activity implements SensorEventListener {
    private static final String TAG = "FrontAlsRoiScanner";
    private SensorManager sensorManager;
    private File latestLuxFile;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        // The view uses physical portrait canvas coordinates supplied by the host scan grid.
        setContentView(new PatternView());
        latestLuxFile = new File(getExternalFilesDir(null), "front_lux.txt");
        sensorManager = getSystemService(SensorManager.class);
        Sensor light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (light != null) {
            // Keep the physical ALS active while framework auto-brightness is deliberately off.
            sensorManager.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override
    protected void onDestroy() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // The host scanner reads the settled sample without a costly sensorservice or logcat dump.
        try (FileOutputStream output = new FileOutputStream(latestLuxFile, false)) {
            output.write(Float.toString(event.values[0]).getBytes());
        } catch (IOException error) {
            Log.w(TAG, "Could not save latest ALS sample", error);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private final class PatternView extends View {
        private final Paint paint = new Paint();
        private final int x = getIntent().getIntExtra("x", -1);
        private final int y = getIntent().getIntExtra("y", -1);
        private final int width = getIntent().getIntExtra("width", 160);
        private final int height = getIntent().getIntExtra("height", 160);

        PatternView() {
            super(ScannerActivity.this);
            paint.setColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.BLACK);
            // Negative coordinates represent the baseline: no bright rectangle is drawn.
            if (x >= 0 && y >= 0) {
                canvas.drawRect(x, y, x + width, y + height, paint);
            }
        }
    }
}
