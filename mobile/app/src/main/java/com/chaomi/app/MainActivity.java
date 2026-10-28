package com.chaomi.app;

import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 主界面：天气卡片 + 一级菜单（信息流 / 资讯）+ 社区信息流 + RSS 资讯。
 *
 * 资讯数据全部由服务器拉取、解析、翻译后存库，App 只读 API：
 *   · 断网也能看已缓存的中文译文
 *   · 不直连任何第三方 RSS 源，省流量也避免被墙
 */
public class MainActivity extends AppCompatActivity implements Realtime.Listener {

    private static final int REQ_AUTH = 8100;
    private static final int REQ_LOC = 8200;

    // 一级菜单
    private MaterialButton tabFeed, tabRss, btnAccount;
    private View feedPane, rssPane;
    private FloatingActionButton fab;

    // 信息流
    private RecyclerView feedView;
    private SwipeRefreshLayout swipe;
    private MaterialButton fLatest, fHot, fMine, fSearch, feedSearchGo;
    private View feedSearchBox;
    private EditText feedSearch;
    private LinearLayout tagRow;
    private final List<Post> feedList = new ArrayList<>();
    private FeedAdapter feedAdapter;
    private String feedSort = "";
    private boolean feedMine = false;
    private String feedQuery = "";
    private String feedTag = "";

    // 资讯
    private RecyclerView rssView;
    private SwipeRefreshLayout rssSwipe;
    private LinearLayout rssCatRow;
    private MaterialButton rssMore;
    private final List<RssItem> rssList = new ArrayList<>();
    private RssAdapter rssAdapter;
    private String rssCat = "";
    private long rssBefore = 0;
    private boolean rssHasMore = true;
    private boolean rssLoading = false;
    private boolean rssLoadedOnce = false;
    private final java.util.Set<Long> rssSeen = new java.util.HashSet<>();

    // 天气
    private LinearLayout wxCard, wxDays;
    private TextView wxCity, wxIcon, wxTemp, wxText, wxSub, wxSwitch;
    private String wxCityOverride = "";

    private Realtime realtime;
    private EditorSheet activeSheet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.fit(this);
        Session.restore(this);

        bindViews();
        wireActions();

        paintAccount();
        loadFeed();
        loadTags();
        initWeather();
        loadRssCats();
        showTab(true);

        startKeepAlive();
        syncAdRules();

        realtime = new Realtime(this);
        Api.bg(() -> {
            try {
                realtime.connect();
            } catch (Exception e) {
                runOnUiThread(() -> toast("实时通道连接失败"));
            }
        });
    }

    private void bindViews() {
        tabFeed = findViewById(R.id.tabFeed);
        tabRss = findViewById(R.id.tabRss);
        btnAccount = findViewById(R.id.btnAccount);
        fab = findViewById(R.id.fab);
        feedPane = findViewById(R.id.feedPane);
        rssPane = findViewById(R.id.rssPane);

        feedView = findViewById(R.id.feed);
        swipe = findViewById(R.id.swipe);
        tagRow = findViewById(R.id.tagRow);
        fLatest = findViewById(R.id.fLatest);
        fHot = findViewById(R.id.fHot);
        fMine = findViewById(R.id.fMine);
        fSearch = findViewById(R.id.fSearch);
        feedSearchBox = findViewById(R.id.feedSearchBox);
        feedSearch = findViewById(R.id.feedSearch);
        feedSearchGo = findViewById(R.id.feedSearchGo);

        rssView = findViewById(R.id.rssList);
        rssSwipe = findViewById(R.id.rssSwipe);
        rssCatRow = findViewById(R.id.rssCatRow);
        rssMore = findViewById(R.id.rssMore);

        wxCard = findViewById(R.id.wxCard);
        wxDays = findViewById(R.id.wxDays);
        wxCity = findViewById(R.id.wxCity);
        wxIcon = findViewById(R.id.wxIcon);
        wxTemp = findViewById(R.id.wxTemp);
        wxText = findViewById(R.id.wxText);
        wxSub = findViewById(R.id.wxSub);
        wxSwitch = findViewById(R.id.wxSwitch);

        feedView.setLayoutManager(new LinearLayoutManager(this));
        feedAdapter = new FeedAdapter(feedList, this::openDetail, this::like);
        feedView.setAdapter(feedAdapter);

        rssView.setLayoutManager(new LinearLayoutManager(this));
        rssAdapter = new RssAdapter(rssList, this::openRss);
        rssView.setAdapter(rssAdapter);
    }

    private void wireActions() {
        swipe.setOnRefreshListener(this::loadFeed);
        rssSwipe.setOnRefreshListener(this::refreshRssAndReload);

        tabFeed.setOnClickListener(v -> showTab(true));
        tabRss.setOnClickListener(v -> showTab(false));
        fab.setOnClickListener(v -> openComposer());
        btnAccount.setOnClickListener(v -> onAccountClick());

        findViewById(R.id.btnAdSkip).setOnClickListener(v ->
                startActivity(new Intent(this, AdSkipActivity.class)));
        findViewById(R.id.btnNode).setOnClickListener(v ->
                startActivity(new Intent(this, NodeActivity.class)));

        fLatest.setOnClickListener(v -> { feedSort = ""; feedMine = false; loadFeed(); });
        fHot.setOnClickListener(v -> { feedSort = "hot"; feedMine = false; loadFeed(); });
        fMine.setOnClickListener(v -> {
            if (!Session.loggedIn(this)) { promptLogin(); return; }
            feedMine = true;
            feedSort = "";
            loadFeed();
        });
        fSearch.setOnClickListener(v -> {
            boolean show = feedSearchBox.getVisibility() != View.VISIBLE;
            feedSearchBox.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) feedSearch.requestFocus();
        });
        feedSearchGo.setOnClickListener(v -> doFeedSearch());
        feedSearch.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doFeedSearch(); return true; }
            return false;
        });

        rssMore.setOnClickListener(v -> loadRss(false));
        wxSwitch.setOnClickListener(v -> pickWeatherCity());
        wxCard.setOnClickListener(v -> {
            // 天气不可用时点卡片直接重试
            if ("天气不可用".contentEquals(wxCity.getText())) {
                initWeather();
            }
        });
    }

    // ---------- 一级菜单切换 ----------

    private void showTab(boolean feed) {
        feedPane.setVisibility(feed ? View.VISIBLE : View.GONE);
        rssPane.setVisibility(feed ? View.GONE : View.VISIBLE);
        fab.setVisibility(feed ? View.VISIBLE : View.GONE);

        tabFeed.setTextColor(ContextCompat.getColor(this,
                feed ? R.color.chaomi_accent : R.color.chaomi_text_dim));
        tabRss.setTextColor(ContextCompat.getColor(this,
                feed ? R.color.chaomi_text_dim : R.color.chaomi_accent));

        if (!feed) {
            loadRssCats();
            if (!rssLoadedOnce) {
                loadRss(true);
            }
        }
    }

    // ========== 天气 ==========

    private WeatherLoader weatherLoader;

    /**
     * 天气加载：交给 WeatherLoader 处理（三路并行 + 5 秒兜底）。
     * 这里只负责把结果画到卡片上，不做任何定位/网络判断。
     */
    private void initWeather() {
        wxCity.setText("定位中…");
        wxText.setText("正在获取天气");

        weatherLoader = new WeatherLoader(this, new WeatherLoader.Callback() {
            @Override
            public void onWeather(WeatherCard card) {
                paintWeather(card);
            }

            @Override
            public void onFailed(String reason) {
                wxCity.setText("天气不可用");
                wxText.setText("点击右侧重试");
                wxSub.setText(reason == null ? "" : reason);
            }
        });
        weatherLoader.start();
    }

    private void paintWeather(WeatherCard c) {
        if (c == null || c.now == null) return;
        String city = c.city == null ? "" : c.city;
        String region = c.region == null ? "" : c.region;
        wxCity.setText(city.isEmpty() ? "当前位置"
                : (region.isEmpty() || region.equals(city) ? city : city + " · " + region));
        wxIcon.setText(c.now.icon == null ? "🌡️" : c.now.icon);
        wxTemp.setText(String.valueOf(Math.round(c.now.tempC)));
        wxText.setText(c.now.text == null ? "" : c.now.text);
        wxSub.setText(String.format(java.util.Locale.CHINA,
                "体感 %d° · 湿度 %d%% · 风 %dkm/h",
                Math.round(c.now.feelsC), c.now.humidity, Math.round(c.now.windKph)));

        wxDays.removeAllViews();
        if (c.days == null) return;
        int n = Math.min(c.days.size(), 5);
        for (int i = 0; i < n; i++) {
            WeatherDay d = c.days.get(i);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(0x14FFFFFF);
            cell.setBackground(bg);
            cell.setPadding(dp(8), dp(6), dp(8), dp(6));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginEnd(dp(6));
            cell.setLayoutParams(lp);

            cell.addView(smallText(i == 0 ? "今天" : weekdayOf(d.date), 11, 0xFFB9D2F0));
            cell.addView(smallText(d.icon == null ? "" : d.icon, 16, 0xFFFFFFFF));
            cell.addView(smallText(Math.round(d.maxC) + "°/" + Math.round(d.minC) + "°", 12, 0xFFFFFFFF));
            wxDays.addView(cell);
        }
    }

    private TextView smallText(String s, int sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private String weekdayOf(String date) {
        try {
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd",
                    java.util.Locale.CHINA);
            java.util.Date d = in.parse(date);
            String[] names = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTime(d);
            return names[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1];
        } catch (Exception e) {
            return "";
        }
    }

    private void pickWeatherCity() {
        EditText et = new EditText(this);
        et.setHint("城市名，如 北京 / 上海");
        et.setSingleLine(true);
        et.setText(WeatherLoader.chosenCity(this));
        int pad = dp(14);
        et.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle("设置天气城市")
                .setMessage("留空则自动定位（系统定位 → IP 定位）。")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    String city = et.getText().toString().trim();
                    WeatherLoader.setChosenCity(this, city);
                    if (weatherLoader != null) weatherLoader = null;
                    initWeather();
                })
                .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LOC) {
            boolean granted = false;
            for (int r : results) if (r == PackageManager.PERMISSION_GRANTED) granted = true;
            if (granted) {
                // 授权后重新加载，用真实定位覆盖 IP 结果
                initWeather();
            }
        }
    }

    // ========== 资讯（RSS） ==========

    /**
     * 加载二级分类。
     *
     * 之前分类不显示的原因：只在 onCreate 调一次，若那时网络未就绪就永远空着。
     * 现在：每次进入资讯页都尝试刷新，且失败会重试一次。
     */
    private void loadRssCats() {
        loadRssCats(0);
    }

    private void loadRssCats(int attempt) {
        Api.bg(() -> {
            try {
                List<RssCat> cats = Api.rssCats();
                if (cats == null || cats.isEmpty()) {
                    if (attempt < 2) {
                        Thread.sleep(1200);
                        loadRssCats(attempt + 1);
                    }
                    return;
                }
                runOnUiThread(() -> {
                    rssCatRow.removeAllViews();
                    rssCatRow.addView(makeRssChip("全部", "", rssCat.isEmpty()));
                    for (RssCat c : cats) {
                        if (c == null || c.key == null) continue;
                        rssCatRow.addView(makeRssChip(
                                c.name + " " + c.count, c.key, c.key.equals(rssCat)));
                    }
                });
            } catch (Exception e) {
                if (attempt < 2) {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ignored) {
                    }
                    loadRssCats(attempt + 1);
                }
            }
        });
    }

    private TextView makeRssChip(String label, String key, boolean active) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(11);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(11), dp(5), dp(11), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        tv.setLayoutParams(lp);
        paintRssChip(tv, active);
        tv.setTag(key == null ? "" : key);
        tv.setOnClickListener(v -> {
            String k = String.valueOf(v.getTag() == null ? "" : v.getTag());
            if (k.equals(rssCat)) return;
            rssCat = k;
            for (int idx = 0; idx < rssCatRow.getChildCount(); idx++) {
                View c = rssCatRow.getChildAt(idx);
                paintRssChip((TextView) c, c == v);
            }
            loadRss(true);
        });
        return tv;
    }

    private void paintRssChip(TextView tv, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(22));
        g.setColor(on ? ContextCompat.getColor(this, R.color.chaomi_accent_2)
                : ContextCompat.getColor(this, R.color.chaomi_surface_2));
        g.setStroke(1, on ? ContextCompat.getColor(this, R.color.chaomi_accent)
                : ContextCompat.getColor(this, R.color.chaomi_border));
        tv.setBackground(g);
        tv.setTextColor(on ? Color.WHITE : ContextCompat.getColor(this, R.color.chaomi_text_dim));
    }

    /**
     * 拉资讯列表。
     *
     * @param reset true=重载第一页（切分类/首次进入）
     *              false=加载更多
     *
     * 注意：这只是**读服务端已存的数据**。要真正拉新报道，
     * 下拉刷新会先调 refreshRss()，见 rssSwipe 的回调。
     */
    // 单次请求条数：需求 5~15 条。取 10 是体验与流量的平衡点。
    private static final int RSS_PAGE_SIZE = 10;

    private void loadRss(boolean reset) {
        if (rssLoading) return;
        if (reset) {
            rssBefore = 0;
            rssHasMore = true;
        }
        if (!rssHasMore && !reset) return;

        rssLoading = true;
        if (reset) rssSwipe.setRefreshing(true);
        rssMore.setVisibility(View.GONE);

        final String cat = rssCat;
        final long before = rssBefore;

        Api.bg(() -> {
            try {
                Api.RssPage page = Api.rssItems(cat, before, RSS_PAGE_SIZE);
                runOnUiThread(() -> {
                    rssLoading = false;
                    rssSwipe.setRefreshing(false);
                    rssLoadedOnce = true;
                    rssBefore = page.nextBefore;
                    rssHasMore = page.hasMore;

                    if (reset) {
                        // 新一轮 = 全新一页，丢掉上一轮的全部内容（不累积）
                        rssList.clear();
                        rssSeen.clear();
                        rssAdapter.notifyDataSetChanged();
                    }
                    int added = 0;
                    for (RssItem it : page.list) {
                        if (rssSeen.contains(it.id)) continue;
                        rssSeen.add(it.id);
                        rssList.add(it);
                        added++;
                    }
                    rssAdapter.notifyDataSetChanged();
                    rssMore.setVisibility(rssHasMore ? View.VISIBLE : View.GONE);

                    if (rssList.isEmpty()) {
                        toast("暂无资讯，下拉可让服务器重新抓取");
                    } else if (added == 0 && rssHasMore) {
                        // 本页全是重复的，继续往后翻
                        loadRss(false);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    rssLoading = false;
                    rssSwipe.setRefreshing(false);
                    toast("资讯加载失败：" + e.getMessage());
                });
            }
        });
    }

    /**
     * 下拉刷新 = 让服务器抓新报道 + 重载列表。
     *
     * 之前的 bug：下拉只重读了已有数据，服务器端 15 分钟才拉一次，
     * 所以用户永远看不到「新报道」。现在下拉会主动触发服务端拉取。
     */
    private void refreshRssAndReload() {
        toast("正在让服务器抓取最新报道…");
        Api.bg(() -> {
            boolean triggered = true;
            String note = "";
            try {
                Api.rssRefresh();
            } catch (Exception e) {
                // 429 = 5 分钟内已刷过，不算错误
                triggered = false;
                note = e.getMessage() == null ? "" : e.getMessage();
            }
            // 服务端拉取是异步的，等一会儿再读，否则读到的还是旧数据
            if (triggered) {
                try {
                    Thread.sleep(6000);
                } catch (InterruptedException ignored) {
                }
            }
            final boolean okd = triggered;
            final String msg = note;
            runOnUiThread(() -> {
                loadRssCats();
                loadRss(true);
                if (!okd) {
                    toast(msg.contains("频繁") ? "刚抓取过，稍后再试" : "抓取请求失败");
                } else {
                    toast("已通知服务器抓取，稍候下拉查看");
                }
            });
        });
    }

    /**
     * 点资讯 → App 直接打开原文（请求不经过服务器）。
     *
     * 阅读体验由客户端自己解决：WebView 加载后注入阅读样式（见 RssReaderActivity）。
     * 这样既不消耗服务器带宽，也不把用户 IP 暴露给第三方代理。
     */
    private void openRss(RssItem it) {
        if (it == null || it.link == null || it.link.isEmpty()) {
            toast("该资讯没有原文链接");
            return;
        }
        Intent i = new Intent(this, RssReaderActivity.class);
        i.putExtra("url", it.link);
        i.putExtra("title", it.displayTitle());
        i.putExtra("link", it.link);
        startActivity(i);
    }

    // ---------- 账号 ----------

    private void onAccountClick() {
        if (!Session.loggedIn(this)) {
            promptLogin();
            return;
        }
        startActivity(new Intent(this, ProfileActivity.class));
    }

    private void syncAdRules() {
        Api.bg(() -> {
            try {
                RuleSync.Result r = RuleSync.sync(this, false);
                if (r.changed) android.util.Log.i("chaomi", "规则已更新: " + r.message);
            } catch (Exception ignored) {
            }
        });
    }

    private void promptLogin() {
        new AlertDialog.Builder(this)
                .setTitle("需要登录")
                .setMessage("发布、评论、点赞需要账号。注册只要邮箱和密码。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去登录", (d, w) ->
                        startActivityForResult(new Intent(this, AuthActivity.class), REQ_AUTH))
                .show();
    }

    private void paintAccount() {
        if (Session.loggedIn(this)) {
            String n = Session.nick(this);
            btnAccount.setText(n.length() > 6 ? n.substring(0, 6) : n);
            btnAccount.setTextColor(ContextCompat.getColor(this, R.color.chaomi_gold));
        } else {
            btnAccount.setText("登录");
            btnAccount.setTextColor(ContextCompat.getColor(this, R.color.chaomi_accent));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if ((requestCode == EditorSheet.REQ_PICK || requestCode == EditorSheet.REQ_CAMERA)
                && activeSheet != null) {
            activeSheet.onPickResult(resultCode, data);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_AUTH && resultCode == RESULT_OK) {
            paintAccount();
            loadFeed();
            loadTags();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Session.restore(this);
        paintAccount();
    }

    // ---------- 信息流 ----------

    private void paintFeedSort() {
        fLatest.setTextColor(feedSort.isEmpty() && !feedMine ? ContextCompat.getColor(this, R.color.chaomi_accent) : ContextCompat.getColor(this, R.color.chaomi_text_dim));
        fHot.setTextColor("hot".equals(feedSort) ? ContextCompat.getColor(this, R.color.chaomi_accent) : ContextCompat.getColor(this, R.color.chaomi_text_dim));
        fMine.setTextColor(feedMine ? ContextCompat.getColor(this, R.color.chaomi_accent) : ContextCompat.getColor(this, R.color.chaomi_text_dim));
        fSearch.setTextColor((!feedQuery.isEmpty() || !feedTag.isEmpty()) ? ContextCompat.getColor(this, R.color.chaomi_accent) : ContextCompat.getColor(this, R.color.chaomi_text_dim));
    }

    private void doFeedSearch() {
        feedQuery = feedSearch.getText().toString().trim();
        feedMine = false;
        loadFeed();
    }

    private void loadFeed() {
        final String sort = feedSort;
        final boolean mine = feedMine;
        final String q = feedQuery;
        final String tag = feedTag;
        Api.bg(() -> {
            try {
                List<Post> list = Api.feed(0, tag, q, sort, mine);
                runOnUiThread(() -> {
                    feedList.clear();
                    feedList.addAll(list);
                    feedAdapter.notifyDataSetChanged();
                    swipe.setRefreshing(false);
                    paintFeedSort();
                    if (feedList.isEmpty()) {
                        String tip = mine ? "你还没发过内容"
                                : (!q.isEmpty() || !tag.isEmpty()) ? "没有匹配的内容"
                                : "还没有内容，点右下角 + 发第一条吧";
                        toast(tip);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> { swipe.setRefreshing(false); toast("加载失败：" + e.getMessage()); });
            }
        });
    }

    private void loadTags() {
        Api.bg(() -> {
            try {
                List<Map<String, Object>> tags = Api.hotTags();
                runOnUiThread(() -> {
                    tagRow.removeAllViews();
                    tagRow.addView(makeTagChip("全部", "", true));
                    for (Map<String, Object> t : tags) {
                        Object tag = t.get("tag");
                        if (tag == null) continue;
                        tagRow.addView(makeTagChip("#" + tag, String.valueOf(tag), false));
                        if (tagRow.getChildCount() >= 12) break;
                    }
                });
            } catch (Exception ignored) {
            }
        });
    }

    private TextView makeTagChip(String label, String tag, boolean active) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(11);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(22, 10, 22, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(12);
        tv.setLayoutParams(lp);
        paintTagChip(tv, active);
        tv.setTag(tag);
        tv.setOnClickListener(v -> {
            feedTag = String.valueOf(v.getTag() == null ? "" : v.getTag());
            feedMine = false;
            for (int i = 0; i < tagRow.getChildCount(); i++) {
                View c = tagRow.getChildAt(i);
                paintTagChip((TextView) c, c == v);
            }
            loadFeed();
        });
        return tv;
    }

    private void paintTagChip(TextView tv, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(24);
        g.setColor(on ? ContextCompat.getColor(this, R.color.chaomi_accent_2) : ContextCompat.getColor(this, R.color.chaomi_surface_2));
        g.setStroke(1, on ? ContextCompat.getColor(this, R.color.chaomi_accent) : ContextCompat.getColor(this, R.color.chaomi_border));
        tv.setBackground(g);
        tv.setTextColor(on ? Color.WHITE : ContextCompat.getColor(this, R.color.chaomi_text_dim));
    }

    // ---------- 交互 ----------

    private void like(Post p) {
        if (!Session.loggedIn(this)) { promptLogin(); return; }
        Api.bg(() -> {
            try {
                Api.LikeResult r = Api.like(p.id);
                runOnUiThread(() -> {
                    p.likes = r.likes;
                    p.liked = r.liked;
                    feedAdapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("点赞失败"));
            }
        });
    }

    private void openDetail(Post p) {
        Intent i = new Intent(this, PostDetailActivity.class);
        i.putExtra("postId", p.id);
        startActivity(i);
    }

    private void openComposer() {
        if (!Session.loggedIn(this)) { promptLogin(); return; }
        EditorSheet sheet = new EditorSheet(this, Session.nick(this), post -> {
            feedList.add(0, post);
            feedAdapter.notifyDataSetChanged();
            feedView.smoothScrollToPosition(0);
            loadTags();
        });
        activeSheet = sheet;
        sheet.show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---- Realtime.Listener ----
    @Override
    public void onNewPost(Post p) {
        runOnUiThread(() -> {
            for (Post x : feedList) if (x.id == p.id) return;
            feedList.add(0, p);
            feedAdapter.notifyDataSetChanged();
            toast("有新帖：" + (p.author == null ? "匿名" : p.author));
        });
    }

    @Override
    public void onLike(long postId, int likes) {
        runOnUiThread(() -> {
            for (Post x : feedList) if (x.id == postId) { x.likes = likes; break; }
            feedAdapter.notifyDataSetChanged();
        });
    }

    @Override
    public void onComment(long postId, Comment c) {
        runOnUiThread(() -> {
            for (Post x : feedList) if (x.id == postId) { x.commentCount++; break; }
            feedAdapter.notifyDataSetChanged();
        });
    }

    @Override
    public void onStatus(String s) {
    }

    /** 启动常驻守护 + 申请通知权限 */
    private void startKeepAlive() {
        KeepAliveService.start(this);
        KeepAliveReceiver.schedule(this);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                            new String[]{"android.permission.POST_NOTIFICATIONS"}, 9101);
                }
            } catch (Exception ignored) {
            }
        }
        if (!AdSkipPrefs.enabled(this) || !KeepAliveService.isAccessibilityOn(this)) {
            AdSkipPrefs.sp(this).edit().putBoolean("asked", true).apply();
        }
    }
}
