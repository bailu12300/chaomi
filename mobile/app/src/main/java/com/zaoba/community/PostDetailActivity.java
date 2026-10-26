package com.zaoba.community;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;
import java.util.List;

public class PostDetailActivity extends AppCompatActivity {

    private long postId;
    private Post post;
    private final List<Comment> comments = new ArrayList<>();
    private CommentAdapter commentAdapter;

    private TextView typeChip, author, time, title, body, likeBtn;
    private LinearLayout imagesRow;
    private RecyclerView commentsView;
    private EditText commentInput;
    private MaterialButton sendBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_post_detail);
        postId = getIntent().getLongExtra("postId", 0);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        typeChip = findViewById(R.id.typeChip);
        author = findViewById(R.id.author);
        time = findViewById(R.id.time);
        title = findViewById(R.id.title);
        body = findViewById(R.id.body);
        imagesRow = findViewById(R.id.imagesRow);
        likeBtn = findViewById(R.id.likeBtn);
        commentsView = findViewById(R.id.comments);
        commentInput = findViewById(R.id.commentInput);
        sendBtn = findViewById(R.id.sendBtn);

        commentsView.setLayoutManager(new LinearLayoutManager(this));
        commentAdapter = new CommentAdapter(comments);
        commentsView.setAdapter(commentAdapter);

        tb.setNavigationOnClickListener(v -> finish());
        likeBtn.setOnClickListener(v -> doLike());
        sendBtn.setOnClickListener(v -> doComment());

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
        author.setText(post.author == null ? "匿名" : post.author);
        time.setText(Utils.formatTime(post.createdAt));
        if (post.title != null && !post.title.isEmpty()) {
            title.setVisibility(View.VISIBLE);
            title.setText(post.title);
        } else {
            title.setVisibility(View.GONE);
        }
        body.setText(post.body == null ? "" : post.body);

        imagesRow.removeAllViews();
        if (post.images != null) {
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

        likeBtn.setText("♥ " + post.likes + "  赞");
        comments.clear();
        comments.addAll(pc.comments);
        commentAdapter.notifyDataSetChanged();
    }

    private void doLike() {
        if (post == null) return;
        Api.bg(() -> {
            try {
                int n = Api.like(post.id);
                runOnUiThread(() -> { post.likes = n; likeBtn.setText("♥ " + n + "  赞"); });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "点赞失败", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void doComment() {
        if (post == null) return;
        String text = commentInput.getText().toString().trim();
        if (text.isEmpty()) return;
        SharedPreferences pref = getSharedPreferences("zaoba", MODE_PRIVATE);
        String nick = pref.getString("nick", "匿名");
        Api.bg(() -> {
            try {
                Comment c = Api.addComment(post.id, nick, text);
                runOnUiThread(() -> {
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

    private String label(String t) {
        if ("link".equals(t)) return "链接";
        if ("image".equals(t)) return "图片";
        if ("article".equals(t)) return "公众号";
        return "文字";
    }
}
