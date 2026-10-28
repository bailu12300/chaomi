package com.chaomi.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

/**
 * 天气加载器：保证**任何情况下 5 秒内一定有结果**，绝不停在「定位中」。
 *
 * 为什么之前会一直「定位中」：
 *   1. getLastKnownLocation 在多数设备返回 null（从没定位过），代码直接 return 不回调
 *   2. 只靠 IP 定位时，服务端 ip-api 超时会把整条链路拖到 20 秒以上
 *   3. 任一步失败都没有兜底文案，UI 停在初始状态
 *
 * 现在的策略（三路并行 + 定时兜底）：
 *   A. 立刻用「上次成功的城市」占位显示（本地缓存，零延迟）
 *   B. 立刻请求服务端按 IP 定位（服务端已把超时压到 1.8 秒）
 *   C. 同时请求系统定位；拿到了就覆盖 A/B 的结果
 *   D. 5 秒定时兜底：还没结果就直接请求默认城市，保证不空白
 */
class WeatherLoader {

    interface Callback {
        /** 拿到天气（可能来自缓存 / IP / GPS） */
        void onWeather(WeatherCard card);

        /** 彻底失败，给用户一个可点的占位 */
        void onFailed(String reason);
    }

    private static final String PREF = "chaomi_wx";
    private static final String K_CITY = "city";       // 用户手选城市
    private static final String K_LAST = "last_payload"; // 上次成功的天气 JSON

    private final Context ctx;
    private final Callback cb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean finished = false;
    private LocationManager lm;
    private LocationListener listener;

    WeatherLoader(Context ctx, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.cb = cb;
    }

    private android.content.SharedPreferences sp() {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 用户手选的城市；空表示自动 */
    static String chosenCity(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(K_CITY, "");
    }

    static void setChosenCity(Context c, String city) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(K_CITY, city == null ? "" : city).apply();
    }

    /** 开始加载 */
    void start() {
        // A) 先显示上次缓存（零延迟，用户立刻看到内容而不是「定位中」）
        String cached = sp().getString(K_LAST, "");
        if (cached != null && !cached.isEmpty()) {
            try {
                WeatherCard c = Api.gsonFromCache(cached);
                if (c != null && c.now != null) {
                    finish(c, "cache");
                }
            } catch (Exception ignored) {
            }
        }

        // D) 5 秒兜底：还没拿到就硬请求默认城市
        ui.postDelayed(() -> {
            if (finished) return;
            Api.bg(() -> {
                try {
                    WeatherCard c = Api.weatherByCity("北京");
                    finishOnUi(c, "fallback");
                } catch (Exception e) {
                    finishFailOnUi("天气服务暂不可用");
                }
            });
        }, 5000);

        // 用户手选了城市：直接按城市查，不用定位
        String city = chosenCity(ctx);
        if (city != null && !city.isEmpty()) {
            Api.bg(() -> {
                try {
                    WeatherCard c = Api.weatherByCity(city);
                    finishOnUi(c, "city");
                } catch (Exception e) {
                    finishFailOnUi("天气获取失败：" + e.getMessage());
                }
            });
            return;
        }

        // B) 立刻走 IP 定位（不依赖权限，成功率最高）
        Api.bg(() -> {
            try {
                WeatherCard c = Api.weather(0, 0);
                finishOnUi(c, "ip");
            } catch (Exception ignored) {
                // 失败不报错，等 GPS 或兜底
            }
        });

        // C) 同时尝试系统定位，拿到更准的位置就覆盖
        startSystemLocation();
    }

    /** 系统定位：lastKnown 优先，没有就主动请求一次单次定位 */
    private void startSystemLocation() {
        if (!hasPermission()) return;
        try {
            lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;

            // 1) 先看有没有历史位置
            Location best = null;
            for (String p : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
                try {
                    if (!lm.isProviderEnabled(p)) continue;
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (SecurityException ignored) {
                }
            }
            // 历史位置太旧（>30 分钟）就当作没有，重新请求
            if (best != null && System.currentTimeMillis() - best.getTime() < 30 * 60 * 1000L) {
                requestByLocation(best);
                return;
            }

            // 2) 主动请求单次定位（这是之前缺的关键一步）
            String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    ? LocationManager.NETWORK_PROVIDER
                    : (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ? LocationManager.GPS_PROVIDER : null);
            if (provider == null) {
                if (best != null) requestByLocation(best);
                return;
            }

            listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    requestByLocation(location);
                    stopLocation();
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override
                public void onProviderEnabled(String provider) {}
                @Override
                public void onProviderDisabled(String provider) {}
            };
            if (Build.VERSION.SDK_INT >= 30) {
                // API 30+：可用 Executor 重载，回调在池线程
                lm.requestLocationUpdates(provider, 0L, 0f,
                        command -> ui.post(command), listener);
            } else {
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper());
            }
            // 4 秒后停止监听，避免耗电
            ui.postDelayed(this::stopLocation, 4000);

            // 有旧位置也先用着
            if (best != null) requestByLocation(best);
        } catch (Exception ignored) {
        }
    }

    private void stopLocation() {
        try {
            if (lm != null && listener != null) lm.removeUpdates(listener);
        } catch (Exception ignored) {
        }
        listener = null;
    }

    private void requestByLocation(Location l) {
        if (l == null) return;
        final double lat = l.getLatitude();
        final double lon = l.getLongitude();
        Api.bg(() -> {
            try {
                WeatherCard c = Api.weather(lat, lon);
                finishOnUi(c, "gps");
            } catch (Exception ignored) {
            }
        });
    }

    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void finishOnUi(WeatherCard c, String src) {
        ui.post(() -> finish(c, src));
    }

    private void finishFailOnUi(String reason) {
        ui.post(() -> {
            if (finished) return;
            finished = true;
            stopLocation();
            cb.onFailed(reason);
        });
    }

    /**
     * 收尾：只接受第一次有效结果，后续 GPS 结果如果更准可以被覆盖。
     *
     * 缓存/IP 的结果先上屏，GPS 结果拿到后覆盖 —— 所以这里不能简单 return。
     */
    private void finish(WeatherCard c, String src) {
        if (c == null || c.now == null) return;
        if ("cache".equals(src)) {
            // 缓存只用于快速占位，不标记完成
            cb.onWeather(c);
            return;
        }
        if (finished && "gps".equals(src) == false) {
            // 已完成且不是更准的 GPS 结果，忽略
            return;
        }
        finished = true;
        stopLocation();
        // 缓存最新结果，下次启动秒出
        try {
            sp().edit().putString(K_LAST, Api.gsonToCache(c)).apply();
        } catch (Exception ignored) {
        }
        cb.onWeather(c);
    }
}
