package com.zaoba.community;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class CommentAdapter extends RecyclerView.Adapter<CommentAdapter.VH> {

    private final List<Comment> comments;

    public CommentAdapter(List<Comment> comments) {
        this.comments = comments;
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
    }

    @Override
    public int getItemCount() {
        return comments.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView author, time, body;

        VH(View v) {
            super(v);
            author = v.findViewById(R.id.cAuthor);
            time = v.findViewById(R.id.cTime);
            body = v.findViewById(R.id.cBody);
        }
    }
}
