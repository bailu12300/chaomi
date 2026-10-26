package com.chaomi.app;

import com.google.gson.Gson;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.Map;

/** 社区实时通道（WebSocket），把服务端推送转成回调 */
public class Realtime extends WebSocketClient {

    public interface Listener {
        void onNewPost(Post p);
        void onLike(long postId, int likes);
        void onComment(long postId, Comment c);
        void onStatus(String s);
    }

    private static final Gson gson = new Gson();
    private final Listener listener;

    public Realtime(Listener l) {
        super(URI.create("wss://cm01.zy1080.xyz/ws/community"));
        this.listener = l;
    }

    private static long asLong(Object o) {
        if (o instanceof Double) return ((Double) o).longValue();
        if (o instanceof Integer) return ((Integer) o).longValue();
        return 0;
    }

    private static int asInt(Object o) {
        if (o instanceof Double) return ((Double) o).intValue();
        if (o instanceof Integer) return (Integer) o;
        return 0;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        if (listener != null) listener.onStatus("open");
    }

    @Override
    public void onMessage(String message) {
        try {
            Map<String, Object> root = gson.fromJson(message, Map.class);
            String type = (String) root.get("type");
            if (listener == null) return;
            if ("new_post".equals(type)) {
                Post p = gson.fromJson(gson.toJson(root.get("post")), Post.class);
                listener.onNewPost(p);
            } else if ("like".equals(type)) {
                listener.onLike(asLong(root.get("postId")), asInt(root.get("likes")));
            } else if ("new_comment".equals(type)) {
                Comment c = gson.fromJson(gson.toJson(root.get("comment")), Comment.class);
                listener.onComment(asLong(root.get("postId")), c);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        if (listener != null) listener.onStatus("closed");
    }

    @Override
    public void onError(Exception ex) {
        if (listener != null) listener.onStatus("error");
    }
}
