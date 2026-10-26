package com.zaoba.community;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.squareup.picasso.Picasso;

import java.util.List;

public class FeedAdapter extends RecyclerView.Adapter<FeedAdapter.VH> {

    public interface OnItemClick { void open(Post p); }
    public interface OnLikeClick { void like(Post p); }

    private final List<Post> posts;
    private final OnItemClick itemClick;
    private final OnLikeClick likeClick;

    public FeedAdapter(List<Post> posts, OnItemClick ic, OnLikeClick lc) {
        this.posts = posts;
        this.itemClick = ic;
        this.likeClick = lc;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_post, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Post p = posts.get(pos);
        h.typeChip.setText(typeLabel(p.type));
        h.author.setText(p.author == null ? "匿名" : p.author);
        h.time.setText(Utils.formatTime(p.createdAt));

        if (p.title != null && !p.title.isEmpty()) {
            h.title.setVisibility(View.VISIBLE);
            h.title.setText(p.title);
        } else {
            h.title.setVisibility(View.GONE);
        }

        String bodyText = bodyPreview(p);
        if (bodyText != null && !bodyText.isEmpty()) {
            h.body.setVisibility(View.VISIBLE);
            h.body.setText(bodyText);
        } else {
            h.body.setVisibility(View.GONE);
        }

        // 图片
        h.imagesRow.removeAllViews();
        if (p.images != null && !p.images.isEmpty()) {
            h.imagesScroll.setVisibility(View.VISIBLE);
            int max = Math.min(p.images.size(), 3);
            for (int i = 0; i < max; i++) {
                ImageView iv = new ImageView(h.itemView.getContext());
                int size = (int) (110 * h.itemView.getContext().getResources().getDisplayMetrics().density);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.setMargins(0, 0, 8, 0);
                iv.setLayoutParams(lp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundResource(android.R.color.darker_gray);
                Picasso.get().load(Utils.fullUrl(p.images.get(i))).into(iv);
                h.imagesRow.addView(iv);
            }
        } else {
            h.imagesScroll.setVisibility(View.GONE);
        }

        h.likeBtn.setText("♥ " + p.likes);
        h.commentBtn.setText("💬 " + p.commentCount);

        h.itemView.setOnClickListener(v -> { if (itemClick != null) itemClick.open(p); });
        h.likeBtn.setOnClickListener(v -> { if (likeClick != null) likeClick.like(p); });
        h.commentBtn.setOnClickListener(v -> { if (itemClick != null) itemClick.open(p); });
    }

    private String bodyPreview(Post p) {
        if (p.body != null && !p.body.isEmpty()) return p.body;
        if ("link".equals(p.type) && p.url != null) return p.url;
        if ("article".equals(p.type) && p.title != null) return "公众号图文 · " + p.title;
        return "";
    }

    private String typeLabel(String t) {
        if ("link".equals(t)) return "链接";
        if ("image".equals(t)) return "图片";
        if ("article".equals(t)) return "公众号";
        return "文字";
    }

    @Override
    public int getItemCount() {
        return posts.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView typeChip, author, time, title, body, likeBtn, commentBtn;
        LinearLayout imagesRow;
        View imagesScroll;

        VH(View v) {
            super(v);
            typeChip = v.findViewById(R.id.typeChip);
            author = v.findViewById(R.id.author);
            time = v.findViewById(R.id.time);
            title = v.findViewById(R.id.title);
            body = v.findViewById(R.id.body);
            likeBtn = v.findViewById(R.id.likeBtn);
            commentBtn = v.findViewById(R.id.commentBtn);
            imagesRow = v.findViewById(R.id.imagesRow);
            imagesScroll = v.findViewById(R.id.imagesScroll);
        }
    }
}
