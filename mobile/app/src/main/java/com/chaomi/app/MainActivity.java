package com.chaomi.app;

import androidx.core.content.ContextCompat;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
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

public class MainActivity extends AppCompatActivity implements Realtime.Listener {

    private static final int REQ_AUTH = 8100;

    private RecyclerView feedView, marketView;
    private SwipeRefreshLayout swipe, marketSwipe;
    private MaterialButton tabFeed, tabMarket, btnAccount;
    private MaterialButton fLatest, fHot, fMine, fSearch, feedSearchGo;
    private MaterialButton sortLatest, sortPriceAsc, sortPriceDesc, sortChange;
    private FloatingActionButton fab;
    private View feedPane, marketPane, feedSearchBox;
    private EditText hwSearch, feedSearch;
    private LinearLayout catRow, tagRow;
    private TextView marketHint;

    private final List<Post> feedList = new ArrayList<>();
    private FeedAdapter feedAdapter;
    // Amazon 显卡行情（替代原多品类行情）
    private final List<GpuModel> gpuList = new ArrayList<>();
    private GpuAdapter gpuAdapter;
    private double fxRate = 0;

    // 信息流状态
    private String feedSort = "";     // "" | hot
    private boolean feedMine = false;
    private String feedQuery = "";
    private String feedTag = "";

    // 行情状态
    private String curCat = "";
    private String curSort = "";
    private String curQuery = "";
    private String moversWindow = "";
    private int hwTotal = 0;
    private int hwRounds = 0;
    private long hwUpdatedAt = 0;
    private boolean marketLoaded = false;

    private Realtime realtime;
    private EditorSheet activeSheet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.fit(this);
        Session.restore(this);

        feedView = findViewById(R.id.feed);
        marketView = findViewById(R.id.market);
        swipe = findViewById(R.id.swipe);
        marketSwipe = findViewById(R.id.marketSwipe);
        tabFeed = findViewById(R.id.tabFeed);
        tabMarket = findViewById(R.id.tabMarket);
        btnAccount = findViewById(R.id.btnAccount);
        fab = findViewById(R.id.fab);
        feedPane = findViewById(R.id.feedPane);
        marketPane = findViewById(R.id.marketPane);
        hwSearch = findViewById(R.id.hwSearch);
        catRow = findViewById(R.id.catRow);
        tagRow = findViewById(R.id.tagRow);
        marketHint = findViewById(R.id.marketHint);
        sortLatest = findViewById(R.id.sortLatest);
        sortPriceAsc = findViewById(R.id.sortPriceAsc);
        sortPriceDesc = findViewById(R.id.sortPriceDesc);
        sortChange = findViewById(R.id.sortChange);
        fLatest = findViewById(R.id.fLatest);
        fHot = findViewById(R.id.fHot);
        fMine = findViewById(R.id.fMine);
        fSearch = findViewById(R.id.fSearch);
        feedSearchBox = findViewById(R.id.feedSearchBox);
        feedSearch = findViewById(R.id.feedSearch);
        feedSearchGo = findViewById(R.id.feedSearchGo);

        feedView.setLayoutManager(new LinearLayoutManager(this));
        marketView.setLayoutManager(new LinearLayoutManager(this));
        feedAdapter = new FeedAdapter(feedList, this::openDetail, this::like);
        feedView.setAdapter(feedAdapter);
        gpuAdapter = new GpuAdapter(gpuList, this::openGpu);
        marketView.setAdapter(gpuAdapter);

        swipe.setOnRefreshListener(this::loadFeed);
        marketSwipe.setOnRefreshListener(this::loadMarket);
        tabFeed.setOnClickListener(v -> showFeed());
        tabMarket.setOnClickListener(v -> { showMarket(); if (!marketLoaded) loadMarket(); });
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

        sortLatest.setOnClickListener(v -> setSort(""));
        sortPriceAsc.setOnClickListener(v -> setSort("price_asc"));
        sortPriceDesc.setOnClickListener(v -> setSort("price_desc"));
        sortChange.setVisibility(View.GONE); // 涨跌榜依赖多轮采集，暂时隐藏

        hwSearch.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                curQuery = hwSearch.getText().toString().trim();
                loadMarket();
                return true;
            }
            return false;
        });

        showFeed();
        paintAccount();
        loadFeed();
        loadTags();

        startKeepAlive();
        syncAdRules();

        realtime = new Realtime(this);
        Api.bg(() -> {
            try { realtime.connect(); } catch (Exception e) { runOnUiThread(() -> toast("实时通道连接失败")); }
        });
    }

    // ---------- 账号 ----------

    private void onAccountClick() {
        if (!Session.loggedIn(this)) {
            promptLogin();
            return;
        }
        // 已登录 → 打开个人主页（改头像/昵称/看自己的内容/退出）
        startActivity(new Intent(this, ProfileActivity.class));
    }

    /** 后台静默同步一次在线去广告规则 */
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
        if (requestCode == EditorSheet.REQ_PICK && activeSheet != null) {
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
        // 行情自动刷新（采集已在服务器端，App 只负责展示）
        if (marketLoaded) loadMarket();
    }

    // ---------- 视图切换 ----------

    private void showFeed() {
        feedPane.setVisibility(View.VISIBLE);
        marketPane.setVisibility(View.GONE);
        fab.setVisibility(View.VISIBLE);
        tabFeed.setTextColor(0xFFFFFFFF);
        tabFeed.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.chaomi_accent)));
        tabMarket.setTextColor(ContextCompat.getColor(this, R.color.chaomi_text_dim));
        tabMarket.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x00000000));
        paintFeedSort();
    }

    private void showMarket() {
        marketPane.setVisibility(View.VISIBLE);
        feedPane.setVisibility(View.GONE);
        fab.setVisibility(View.GONE);
        tabMarket.setTextColor(0xFFFFFFFF);
        tabMarket.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.chaomi_accent)));
        tabFeed.setTextColor(ContextCompat.getColor(this, R.color.chaomi_text_dim));
        tabFeed.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x00000000));
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

    /** 热门标签行 */
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

    // ---------- 行情 ----------

    private void setSort(String s) {
        curSort = s;
        loadMarket();
    }

    private void loadMarket() {
        final String q = curQuery == null ? "" : curQuery.trim().toLowerCase();
        final String sort = curSort;
        marketSwipe.setRefreshing(true);
        Api.bg(() -> {
            try {
                Api.GpuList res = Api.gpuList();
                List<GpuModel> list = new ArrayList<>(res.list);
                // 本地搜索
                if (!q.isEmpty()) {
                    List<GpuModel> filtered = new ArrayList<>();
                    for (GpuModel m : list) {
                        String hay = ((m.model == null ? "" : m.model) + " "
                                + (m.spec == null ? "" : m.spec) + " "
                                + (m.title == null ? "" : m.title)).toLowerCase();
                        if (hay.contains(q)) filtered.add(m);
                    }
                    list = filtered;
                }
                // 本地排序
                if ("price_asc".equals(sort)) {
                    list.sort((a, b) -> Double.compare(a.priceUsd, b.priceUsd));
                } else if ("price_desc".equals(sort)) {
                    list.sort((a, b) -> Double.compare(b.priceUsd, a.priceUsd));
                } else {
                    list.sort((a, b) -> Integer.compare(numOf(a.model), numOf(b.model)));
                }
                final List<GpuModel> got = list;
                final double rate = res.fx == null ? 0 : res.fx.rate;
                runOnUiThread(() -> {
                    fxRate = rate;
                    gpuList.clear();
                    gpuList.addAll(got);
                    gpuAdapter.notifyDataSetChanged();
                    marketLoaded = true;
                    marketSwipe.setRefreshing(false);
                    hwTotal = got.size();
                    updateHint();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    marketSwipe.setRefreshing(false);
                    toast("行情加载失败：" + e.getMessage());
                });
            }
        });
    }

    /** 型号排序权重：5070 Ti → 5070 */
    private int numOf(String model) {
        if (model == null) return 0;
        StringBuilder sb = new StringBuilder();
        for (char c : model.toCharArray()) {
            if (c >= '0' && c <= '9') sb.append(c);
            else if (sb.length() > 0) break;
        }
        try {
            return Integer.parseInt(sb.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 点击显卡：用系统浏览器打开 Amazon 商品页 */
    private void openGpu(GpuModel m) {
        String url = (m.amazonUrl == null || m.amazonUrl.isEmpty())
                ? "https://www.amazon.com/s?k=" + android.net.Uri.encode("RTX " + m.model)
                : m.amazonUrl;
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)));
        } catch (Exception e) {
            toast("没有可用浏览器");
        }
    }

    private void updateHint() {
        StringBuilder sb = new StringBuilder();
        sb.append("Amazon 显卡 · 共 ").append(hwTotal).append(" 个型号");
        if (fxRate > 0) {
            sb.append(" · 汇率 1 USD ≈ ").append(String.format(java.util.Locale.CHINA, "%.2f", fxRate)).append(" CNY");
        }
        if (hwUpdatedAt > 0) sb.append(" · 更新 ").append(Utils.formatTime(hwUpdatedAt));
        if (!curQuery.isEmpty()) sb.append(" · 搜索「").append(curQuery).append("」");
        marketHint.setText(sb.toString());
        highlightSort();
    }

    private void highlightSort() {
        MaterialButton[] all = {sortLatest, sortPriceAsc, sortPriceDesc};
        String[] keys = {"", "price_asc", "price_desc", "change"};
        for (int i = 0; i < all.length; i++) {
            all[i].setTextColor(keys[i].equals(curSort) ? ContextCompat.getColor(this, R.color.chaomi_accent) : ContextCompat.getColor(this, R.color.chaomi_text_dim));
        }
    }



    private TextView makeChip(String label, String cat, boolean selected) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(26, 14, 26, 14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(14);
        tv.setLayoutParams(lp);
        paintChip(tv, selected);
        tv.setTag(cat);
        tv.setOnClickListener(v -> {
            curCat = String.valueOf(v.getTag() == null ? "" : v.getTag());
            for (int i = 0; i < catRow.getChildCount(); i++) {
                View c = catRow.getChildAt(i);
                paintChip((TextView) c, c == v);
            }
            loadMarket();
        });
        return tv;
    }

    private void paintChip(TextView tv, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(28);
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

    /** 启动常驻守护 + 申请通知权限（前台服务需要通知才不会被折起来） */
    private void startKeepAlive() {
        if (Session.loggedIn(this) || true) {
            KeepAliveService.start(this);
            KeepAliveReceiver.schedule(this);
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                            new String[]{"android.permission.POST_NOTIFICATIONS"}, 9101);
                }
            } catch (Exception ignored) {
            }
        }
        // 首次进入引导授权（只提示一次）
        if (!AdSkipPrefs.enabled(this) || !KeepAliveService.isAccessibilityOn(this)) {
            AdSkipPrefs.sp(this).edit().putBoolean("asked", true).apply();
        }
    }
}
