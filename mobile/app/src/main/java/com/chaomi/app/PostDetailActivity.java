package com.chaomi.app;

import androidx.core.content.ContextCompat;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;
import java.util.List;

public class PostDetailActivity extends AppCompatActivity {

    private static final int REQ_AUTH = 8101;

    private long postId;
    private Post post;
    private final List<Comment> comments = new ArrayList<>();
    private CommentAdapter commentAdapter;

    private TextView typeChip, author, time, title, body, likeBtn, meta;
    private LinearLayout imagesRow, blocksBox;
    private RecyclerView commentsView;
    private EditText commentInput;
    private MaterialButton sendBtn, deleteBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_post_detail);
        Ui.fit(this);
        Session.restore(this);
        postId = getIntent().getLongExtra("postId", 0);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        typeChip = findViewById(R.id.typeChip);
        author = findViewById(R.id.author);
        time = findViewById(R.id.time);
        title = findViewById(R.id.title);
        body = findViewById(R.id.body);
        imagesRow = findViewById(R.id.imagesRow);
        blocksBox = findViewById(R.id.blocksBox);
        likeBtn = findViewById(R.id.likeBtn);
        deleteBtn = findViewById(R.id.deleteBtn);
        commentsView = findViewById(R.id.comments);
        commentInput = findViewById(R.id.commentInput);
        sendBtn = findViewById(R.id.sendBtn);

        commentsView.setLayoutManager(new LinearLayoutManager(this));
        commentAdapter = new CommentAdapter(comments, c -> confirmDeleteComment(c));
        commentsView.setAdapter(commentAdapter);

        tb.setNavigationOnClickListener(v -> finish());
        likeBtn.setOnClickListener(v -> doLike());
        sendBtn.setOnClickListener(v -> doComment());
        deleteBtn.setOnClickListener(v -> confirmDeletePost());

        load();
    }

    private void load() {
        Api.bg(() -> {
            try {
                PostAndComments pc = Api.postDetail(postId);
                runOnUiThread(() -> bind(pc));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "加载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void bind(PostAndComments pc) {
        this.post = pc.post;
        typeChip.setText(label(post.type));
        author.setText((post.author == null ? "匿名" : post.author)
                + (post.views > 0 ? "  ·  " + post.views + " 次浏览" : ""));
        time.setText(Utils.formatTime(post.createdAt));

        if (post.title != null && !post.title.isEmpty()) {
            title.setVisibility(View.VISIBLE);
            title.setText(post.title);
        } else {
            title.setVisibility(View.GONE);
        }

        // 文章类优先用原生块渲染；没有块再退回纯文本
        boolean hasBlocks = post.blocks != null && !post.blocks.isEmpty();
        if (hasBlocks) {
            blocksBox.setVisibility(View.VISIBLE);
            body.setVisibility(View.GONE);
            ArticleRenderer.render(this, blocksBox, post.blocks);
        } else {
            blocksBox.setVisibility(View.GONE);
            body.setVisibility(View.VISIBLE);
            body.setText(post.body == null ? "" : post.body);
        }

        // 有 blocks 时图片已在正文里，避免重复展示
        imagesRow.removeAllViews();
        imagesRow.setVisibility(hasBlocks || post.images == null || post.images.isEmpty()
                ? View.GONE : View.VISIBLE);
        if (!hasBlocks && post.images != null) {
            int h = (int) (200 * getResources().getDisplayMetrics().density);
            for (String u : post.images) {
                ImageView iv = new ImageView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, h);
                lp.setMargins(0, 0, 0, 8);
                iv.setLayoutParams(lp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                Picasso.get().load(Utils.fullUrl(u)).into(iv);
                imagesRow.addView(iv);
            }
        }

        paintLike();
        deleteBtn.setVisibility(pc.mine ? View.VISIBLE : View.GONE);

        comments.clear();
        comments.addAll(pc.comments);
        commentAdapter.notifyDataSetChanged();
    }

    private void paintLike() {
        if (post == null) return;
        likeBtn.setText((post.liked ? "♥ " : "♡ ") + post.likes + "  赞");
        likeBtn.setTextColor(post.liked ? ContextCompat.getColor(this, R.color.chaomi_up) : ContextCompat.getColor(this, R.color.chaomi_accent));
    }

    private void doLike() {
        if (post == null) return;
        if (!Session.loggedIn(this)) {
            Toast.makeText(this, "登录后才能点赞", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, AuthActivity.class));
            return;
        }
        Api.bg(() -> {
            try {
                Api.LikeResult r = Api.like(post.id);
                runOnUiThread(() -> {
                    post.likes = r.likes;
                    post.liked = r.liked;
                    paintLike();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "点赞失败", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void doComment() {
        if (post == null) return;
        if (!Session.loggedIn(this)) {
            promptLogin();
            return;
        }
        String text = commentInput.getText().toString().trim();
        if (text.isEmpty()) return;
        String nick = Session.nick(this);
        Api.bg(() -> {
            try {
                Comment c = Api.addComment(post.id, nick, text);
                runOnUiThread(() -> {
                    c.mine = true;
                    comments.add(c);
                    commentAdapter.notifyDataSetChanged();
                    commentInput.setText("");
                    post.commentCount = comments.size();
                    Toast.makeText(this, "评论成功", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "评论失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void confirmDeletePost() {
        new AlertDialog.Builder(this)
                .setTitle("删除这条内容？")
                .setMessage("删除后评论与点赞一并移除，无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> Api.bg(() -> {
                    try {
                        Api.deletePost(postId);
                        runOnUiThread(() -> {
                            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                            setResult(RESULT_OK);
                            finish();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "删除失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }))
                .show();
    }

    private void confirmDeleteComment(Comment c) {
        if (c == null) return;
        new AlertDialog.Builder(this)
                .setTitle("删除这条评论？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> Api.bg(() -> {
                    try {
                        Api.deleteComment(c.id);
                        runOnUiThread(() -> {
                            comments.remove(c);
                            commentAdapter.notifyDataSetChanged();
                            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "删除失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }))
                .show();
    }

    private void promptLogin() {
        new AlertDialog.Builder(this)
                .setTitle("需要登录")
                .setMessage("登录后才能评论，注册只要邮箱和密码。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去登录", (d, w) ->
                        startActivityForResult(new Intent(this, AuthActivity.class), REQ_AUTH))
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_AUTH && resultCode == RESULT_OK) {
            load();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Session.restore(this);
    }

    private String label(String t) {
        if ("link".equals(t)) return "链接";
        if ("image".equals(t)) return "图片";
        if ("article".equals(t)) return "公众号";
        return "文字";
    }
}
