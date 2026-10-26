package com.chaomi.app;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * 摇一摇广告抑制。
 *
 * 原理：摇一摇广告靠加速度传感器触发跳转，用户根本没想动。
 * 我们无法拦别人的 App 读传感器（跨进程做不到），但可以：
 *   1) 在自己进程内持续监听加速度，检测「刚被摇动」→ 上报/记录
 *   2) 配合无障碍规则，在检测到摇动后的时间窗内主动点掉当前页面的
 *      「取消 / 跳过 / ×」，把摇一摇即将触发的跳转掐断
 *   3) 对自家 App 完全屏蔽该传感器（无障碍服务不依赖它）
 *
 * 由于 Android 不允许跨应用拦截传感器，这里的定位是「快速善后」而不是硬拦截。
 * 上面的窗口期（默认 6 秒）来自服务端下发的 shake 配置。
 */
class ShakeGuard implements SensorEventListener {

    private static final String TAG = "ChaomiShake";
    /** 判定为「摇」的加速度变化阈值（m/s²，已去掉重力） */
    private static final float DEFAULT_THRESHOLD = 2.6f;

    interface OnShake {
        void onShake(float magnitude);
    }

    private final SensorManager sm;
    private final Sensor accel;
    private volatile boolean running;
    private final float[] gravity = new float[3];
    private boolean hasGravity;

    /** 最近一次判定的摇动时间 */
    private volatile long lastShakeAt;
    private volatile float lastMagnitude;
    private volatile float threshold = DEFAULT_THRESHOLD;
    private OnShake cb;

    ShakeGuard(Context c) {
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        accel = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    void setThreshold(float t) {
        if (t > 0.5f && t < 30f) threshold = t;
    }

    void setCallback(OnShake cb) {
        this.cb = cb;
    }

    void start() {
        if (running || sm == null || accel == null) return;
        running = true;
        hasGravity = false;
        // SENSOR_DELAY_UI 足够，省电
        sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI);
        Log.i(TAG, "摇一摇监测已启动");
    }

    void stop() {
        if (!running || sm == null) return;
        running = false;
        sm.unregisterListener(this);
        Log.i(TAG, "摇一摇监测已停止");
    }

    boolean isRunning() {
        return running;
    }

    /** 最近 windowMs 内是否发生过摇动 */
    boolean shookWithin(long windowMs) {
        return lastShakeAt > 0 && SystemClock.elapsedRealtime() - lastShakeAt <= windowMs;
    }

    long lastShakeAt() {
        return lastShakeAt;
    }

    float lastMagnitude() {
        return lastMagnitude;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!running || e == null || e.values == null || e.values.length < 3) return;

        // 低通滤波去掉重力分量
        final float alpha = 0.8f;
        if (!hasGravity) {
            gravity[0] = e.values[0];
            gravity[1] = e.values[1];
            gravity[2] = e.values[2];
            hasGravity = true;
            return;
        }
        gravity[0] = alpha * gravity[0] + (1 - alpha) * e.values[0];
        gravity[1] = alpha * gravity[1] + (1 - alpha) * e.values[1];
        gravity[2] = alpha * gravity[2] + (1 - alpha) * e.values[2];

        float x = e.values[0] - gravity[0];
        float y = e.values[1] - gravity[1];
        float z = e.values[2] - gravity[2];
        float mag = (float) Math.sqrt(x * x + y * y + z * z);

        if (mag > threshold) {
            // 防抖：800ms 内只记一次
            long now = SystemClock.elapsedRealtime();
            if (now - lastShakeAt < 800) return;
            lastShakeAt = now;
            lastMagnitude = mag;
            Log.i(TAG, String.format("检测到摇动 %.2f > %.2f", mag, threshold));
            OnShake c = cb;
            if (c != null) c.onShake(mag);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
