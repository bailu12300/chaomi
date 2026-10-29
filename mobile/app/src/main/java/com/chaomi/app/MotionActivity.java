package com.chaomi.app;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.util.Locale;

/**
 * 运动传感器：实时速度 + 步数。
 *
 * 为什么能在室内也算出速度：
 *   · 步数用系统计步器（TYPE_STEP_COUNTER，硬件级，极省电）
 *     它返回"开机以来的累计步数"，所以要减去首次读到的基准值
 *   · 速度用加速度计做峰值检测估算步频，再按步长换算成 km/h
 *     （GPS 在室内完全不可用，而加速度计随处可见）
 *
 * 兼容性：
 *   · 没有计步器的机型（部分老设备）只显示速度估算，步数区提示不支持
 *   · 没有加速度计的机型（极少）给明确提示，不崩溃
 */
public class MotionActivity extends AppCompatActivity implements SensorEventListener {

    private SensorManager sm;
    private Sensor accel, stepCounter, stepDetector;

    private TextView tvSpeed, tvSpeedUnit, tvSteps, tvCadence, tvState, tvTip;
    private MaterialButton btnReset;

    // ---- 计步 ----
    private long stepBase = -1;      // 首次读到的累计值
    private long stepsNow = 0;       // 本次会话步数
    private long stepsSessionBase = 0;// 重置时的基准
    private boolean hasStepCounter = false;

    // ---- 速度估算 ----
    private final float[] gravity = new float[3];
    private boolean hasGravity = false;
    private long lastStepAt = 0;
    private long lastPeakAt = 0;
    private float lastMag = 0;
    private boolean rising = false;

    /** 步频（步/分钟），用滑动平均平滑 */
    private float cadence = 0;
    /** 步长估算（米）。成人步行约 0.7m，跑步约 1.0m，这里按身高无关的折中值 */
    private static final float STRIDE_M = 0.72f;

    /** UI 刷新节流：传感器回调很密集，没必要每次都刷界面 */
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable uiTask;
    private volatile float curSpeedKmh = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_motion);
        Ui.fit(this);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) tb.setNavigationOnClickListener(v -> finish());

        tvSpeed = findViewById(R.id.mSpeed);
        tvSpeedUnit = findViewById(R.id.mSpeedUnit);
        tvSteps = findViewById(R.id.mSteps);
        tvCadence = findViewById(R.id.mCadence);
        tvState = findViewById(R.id.mState);
        tvTip = findViewById(R.id.mTip);
        btnReset = findViewById(R.id.mReset);

        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sm != null) {
            accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            stepCounter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            stepDetector = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        }
        hasStepCounter = stepCounter != null;

        if (accel == null && stepCounter == null && stepDetector == null) {
            tvState.setText("本机没有可用的运动传感器");
            tvState.setTextColor(ContextCompat.getColor(this, R.color.chaomi_up));
            tvTip.setText("部分平板/模拟器不提供加速度计与计步器，功能无法使用。");
            btnReset.setEnabled(false);
            return;
        }

        if (stepCounter == null) {
            tvTip.setText("本机没有硬件计步器，步数改用加速度计推算（精度略低，且不计入系统步数）。");
        }

        btnReset.setOnClickListener(v -> {
            stepsSessionBase = stepsNow;
            stepBase = -1; // 让下次回调重新取基准
            cadence = 0;
            curSpeedKmh = 0;
            paint();
        });

        startUiTicker();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sm == null) return;
        if (accel != null) {
            sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
        }
        if (stepCounter != null) {
            sm.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_UI);
        } else if (stepDetector != null) {
            sm.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sm != null) sm.unregisterListener(this);
        if (uiTask != null) ui.removeCallbacks(uiTask);
    }

    // ---------- 传感器 ----------

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e == null || e.values == null) return;

        if (e.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            long total = (long) e.values[0];
            if (stepBase < 0) stepBase = total;
            stepsNow = Math.max(0, total - stepBase);
            paint();
            return;
        }

        if (e.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
            stepsNow++;
            onStepDetected();
            paint();
            return;
        }

        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            onAccel(e.values);
        }
    }

    /** 加速度计：去重力 + 峰值检测估算步频 */
    private void onAccel(float[] v) {
        if (v.length < 3) return;
        final float alpha = 0.8f;
        if (!hasGravity) {
            gravity[0] = v[0]; gravity[1] = v[1]; gravity[2] = v[2];
            hasGravity = true;
            return;
        }
        gravity[0] = alpha * gravity[0] + (1 - alpha) * v[0];
        gravity[1] = alpha * gravity[1] + (1 - alpha) * v[1];
        gravity[2] = alpha * gravity[2] + (1 - alpha) * v[2];

        float x = v[0] - gravity[0];
        float y = v[1] - gravity[1];
        float z = v[2] - gravity[2];
        float mag = (float) Math.sqrt(x * x + y * y + z * z);

        long now = System.currentTimeMillis();

        // 峰值检测：先上升穿过阈值，再回落到阈值以下 → 记一步
        final float TH = 1.6f;
        if (mag > lastMag && mag > TH) {
            rising = true;
        } else if (rising && mag < TH * 0.6f) {
            rising = false;
            // 峰值间隔 250~1500ms 才算一步（排除抖动和高频噪声）
            if (now - lastPeakAt > 250 && now - lastPeakAt < 1500) {
                if (!hasStepCounter) {
                    // 没有硬件计步器时，用加速度峰值补步数
                    stepsNow++;
                }
                onStepDetected();
            }
            lastPeakAt = now;
        }
        lastMag = mag;
    }

    /** 记到一步：更新步频与速度 */
    private void onStepDetected() {
        long now = System.currentTimeMillis();
        if (lastStepAt > 0) {
            long dt = now - lastStepAt;
            if (dt > 250 && dt < 2000) {
                float inst = 60000f / dt; // 步/分钟
                // 滑动平均，避免单次抖动导致数字乱跳
                cadence = cadence <= 0 ? inst : (cadence * 0.7f + inst * 0.3f);
                // 速度 = 步频(步/分) × 步长(米) / 1000 → km/h
                curSpeedKmh = cadence * STRIDE_M / 1000f * 60f;
            }
        }
        lastStepAt = now;
    }

    // ---------- UI ----------

    private void startUiTicker() {
        uiTask = new Runnable() {
            @Override
            public void run() {
                // 2 秒没有新步 → 判定为静止，速度归零（避免停下来了还显示 5km/h）
                if (System.currentTimeMillis() - lastStepAt > 2000) {
                    curSpeedKmh = 0;
                    cadence = 0;
                }
                paint();
                ui.postDelayed(this, 600);
            }
        };
        ui.postDelayed(uiTask, 600);
    }

    private void paint() {
        runOnUiThread(() -> {
            tvSpeed.setText(String.format(Locale.CHINA, "%.1f", curSpeedKmh));
            tvSteps.setText(String.valueOf(Math.max(0, stepsNow - stepsSessionBase)));
            tvCadence.setText(cadence > 1
                    ? String.format(Locale.CHINA, "步频 %.0f 步/分 · 步长约 %.2fm", cadence, STRIDE_M)
                    : "步频 —");

            boolean moving = curSpeedKmh > 0.3f;
            tvState.setText(moving ? "运动中" : "静止");
            tvState.setTextColor(ContextCompat.getColor(this,
                    moving ? R.color.chaomi_down : R.color.chaomi_text_dim));
        });
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
