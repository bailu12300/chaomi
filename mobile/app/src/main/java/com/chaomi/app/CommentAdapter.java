package com.chaomi.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class CommentAdapter extends RecyclerView.Adapter<CommentAdapter.VH> {

    public interface OnDelete { void onDelete(Comment c); }

    private final List<Comment> comments;
    private final OnDelete onDelete;

    public CommentAdapter(List<Comment> comments) {
        this(comments, null);
    }

    public CommentAdapter(List<Comment> comments, OnDelete onDelete) {
        this.comments = comments;
        this.onDelete = onDelete;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_comment, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Comment c = comments.get(pos);
        h.author.setText(c.author == null ? "匿名" : c.author);
        h.time.setText(Utils.formatTime(c.createdAt));
        h.body.setText(c.body);
        // 只有自己的评论才显示删除
        h.del.setVisibility(c.mine ? View.VISIBLE : View.GONE);
        h.del.setOnClickListener(v -> {
            if (onDelete != null) onDelete.onDelete(c);
        });
        h.itemView.setOnLongClickListener(v -> {
            if (c.mine && onDelete != null) {
                onDelete.onDelete(c);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return comments.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView author, time, body, del;

        VH(View v) {
            super(v);
            author = v.findViewById(R.id.cAuthor);
            time = v.findViewById(R.id.cTime);
            body = v.findViewById(R.id.cBody);
            del = v.findViewById(R.id.cDelete);
        }
    }
}
