package com.zaoba.community;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
    private final List<HwModel> hwList = new ArrayList<>();
    private HwAdapter hwAdapter;

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
    private ComposerSheet activeSheet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
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
        hwAdapter = new HwAdapter(hwList, this::openModel);
        marketView.setAdapter(hwAdapter);

        swipe.setOnRefreshListener(this::loadFeed);
        marketSwipe.setOnRefreshListener(this::loadMarket);
        tabFeed.setOnClickListener(v -> showFeed());
        tabMarket.setOnClickListener(v -> { showMarket(); if (!marketLoaded) loadMarket(); });
        fab.setOnClickListener(v -> openComposer());
        btnAccount.setOnClickListener(v -> onAccountClick());

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
        sortChange.setOnClickListener(v -> setSort("change"));

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
        new AlertDialog.Builder(this)
                .setTitle(Session.nick(this))
                .setMessage(Session.email(this) + "\n\n要退出登录吗？")
                .setNegativeButton("取消", null)
                .setNeutralButton("我的内容", (d, w) -> {
                    feedMine = true;
                    feedSort = "";
                    showFeed();
                    loadFeed();
                })
                .setPositiveButton("退出", (d, w) -> Api.bg(() -> {
                    try { Api.logout(); } catch (Exception ignored) { }
                    runOnUiThread(() -> {
                        Session.clear(this);
                        paintAccount();
                        feedMine = false;
                        loadFeed();
                        toast("已退出登录");
                    });
                }))
                .show();
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
            btnAccount.setTextColor(0xFFe3b341);
        } else {
            btnAccount.setText("登录");
            btnAccount.setTextColor(0xFF58a6ff);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ComposerSheet.REQ_PICK && activeSheet != null) {
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

    // ---------- 视图切换 ----------

    private void showFeed() {
        feedPane.setVisibility(View.VISIBLE);
        marketPane.setVisibility(View.GONE);
        fab.setVisibility(View.VISIBLE);
        tabFeed.setTextColor(0xFFFFFFFF);
        tabFeed.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF58a6ff));
        tabMarket.setTextColor(0xFF8b949e);
        tabMarket.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x00000000));
        paintFeedSort();
    }

    private void showMarket() {
        marketPane.setVisibility(View.VISIBLE);
        feedPane.setVisibility(View.GONE);
        fab.setVisibility(View.GONE);
        tabMarket.setTextColor(0xFFFFFFFF);
        tabMarket.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF58a6ff));
        tabFeed.setTextColor(0xFF8b949e);
        tabFeed.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x00000000));
    }

    // ---------- 信息流 ----------

    private void paintFeedSort() {
        fLatest.setTextColor(feedSort.isEmpty() && !feedMine ? 0xFF58a6ff : 0xFF8b949e);
        fHot.setTextColor("hot".equals(feedSort) ? 0xFF58a6ff : 0xFF8b949e);
        fMine.setTextColor(feedMine ? 0xFF58a6ff : 0xFF8b949e);
        fSearch.setTextColor((!feedQuery.isEmpty() || !feedTag.isEmpty()) ? 0xFF58a6ff : 0xFF8b949e);
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
        g.setColor(on ? 0xFF1f6feb : 0xFF21262d);
        g.setStroke(1, on ? 0xFF58a6ff : 0xFF30363d);
        tv.setBackground(g);
        tv.setTextColor(on ? Color.WHITE : 0xFF8b949e);
    }

    // ---------- 行情 ----------

    private void setSort(String s) {
        curSort = s;
        loadMarket();
    }

    private void loadMarket() {
        final String cat = curCat, sort = curSort, q = curQuery;
        marketSwipe.setRefreshing(true);
        Api.bg(() -> {
            try {
                List<HwModel> list;
                if ("change".equals(sort)) {
                    list = Api.hwMovers(moversWindow, cat);
                } else {
                    list = Api.hwModels(cat, q, sort);
                }
                final List<HwCat> cats = Api.hwCats();
                final Api.HwStatusRes st = Api.hwStatus();
                final List<HwModel> got = list;
                runOnUiThread(() -> {
                    hwTotal = st.count;
                    hwRounds = st.rounds;
                    hwUpdatedAt = st.updatedAt;
                    hwList.clear();
                    hwList.addAll(got);
                    hwAdapter.notifyDataSetChanged();
                    buildCats(cats);
                    marketLoaded = true;
                    marketSwipe.setRefreshing(false);
                    updateHint();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { marketSwipe.setRefreshing(false); toast("行情加载失败：" + e.getMessage()); });
            }
        });
    }

    private void updateHint() {
        StringBuilder sb = new StringBuilder();
        if ("change".equals(curSort)) {
            sb.append("涨跌榜 · 仅展示合理波动（±40% 内）· 共 ").append(hwList.size()).append(" 条");
            if (hwList.isEmpty()) sb.append(" · 本轮价格无变化，采集积累后自动出现");
        } else {
            sb.append("共 ").append(hwTotal).append(" 个型号");
            if (hwRounds > 0) sb.append(" · 已采集 ").append(hwRounds).append(" 轮");
            if (hwUpdatedAt > 0) sb.append(" · 更新 ").append(Utils.formatTime(hwUpdatedAt));
            if (!curCat.isEmpty()) sb.append(" · ").append(curCat);
            if (!curQuery.isEmpty()) sb.append(" · 搜索「").append(curQuery).append("」");
        }
        marketHint.setText(sb.toString());
        highlightSort();
    }

    private void highlightSort() {
        MaterialButton[] all = {sortLatest, sortPriceAsc, sortPriceDesc, sortChange};
        String[] keys = {"", "price_asc", "price_desc", "change"};
        for (int i = 0; i < all.length; i++) {
            all[i].setTextColor(keys[i].equals(curSort) ? 0xFF58a6ff : 0xFF8b949e);
        }
    }

    private void buildCats(List<HwCat> cats) {
        if (catRow.getChildCount() > 0) return;
        catRow.addView(makeChip("全部", "", true));
        if (cats != null) {
            for (HwCat c : cats) {
                if (c == null || c.cat == null) continue;
                catRow.addView(makeChip(c.cat + " " + c.count, c.cat, false));
            }
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
        g.setColor(on ? 0xFF1f6feb : 0xFF21262d);
        g.setStroke(1, on ? 0xFF58a6ff : 0xFF30363d);
        tv.setBackground(g);
        tv.setTextColor(on ? Color.WHITE : 0xFF8b949e);
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

    private void openModel(HwModel m) {
        Intent i = new Intent(this, ModelDetailActivity.class);
        i.putExtra("key", m.key);
        i.putExtra("model", m.model);
        i.putExtra("brand", m.brand);
        i.putExtra("spec", m.spec);
        i.putExtra("cat", m.catLabel);
        i.putExtra("platform", m.platform);
        i.putExtra("shop", m.shop);
        i.putExtra("title", m.title);
        i.putExtra("price", Utils.priceStr(m.price));
        i.putExtra("changePct", m.changePct);
        i.putExtra("hasChange", m.hasChange);
        startActivity(i);
    }

    private void openComposer() {
        if (!Session.loggedIn(this)) { promptLogin(); return; }
        ComposerSheet sheet = new ComposerSheet(this, Session.nick(this), post -> {
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
}
