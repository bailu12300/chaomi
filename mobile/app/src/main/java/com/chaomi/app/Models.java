package com.chaomi.app;

import java.util.List;

/** 社区帖子 */
class Post {
    long id;
    long userId;
    String type;       // text | image | link | article
    String title;
    String body;
    String cover;
    String url;
    List<String> images;
    List<Block> blocks;   // article 类型的原生块
    String author;
    List<String> tags;
    int likes;
    boolean liked;
    int views;
    int commentCount;
    long createdAt;
}

/** 评论 */
class Comment {
    long id;
    long postId;
    long userId;
    String author;
    String body;
    long createdAt;
    boolean mine;
}

/** 帖子详情 = 帖子 + 评论列表 */
class PostAndComments {
    Post post;
    List<Comment> comments;
    boolean mine;
}

/** 文章原生块：客户端据此原生渲染，不依赖 WebView */
class Block {
    String type;   // p | h2 | h3 | img | quote | code | li | topic
    String text;
    String src;
    String alt;
}

/** 公众号图文解析结果 */
class Article {
    String title;
    String author;
    String cover;
    String content;
    List<String> images;
    String url;
    String summary;
    List<Block> blocks;
    int count;
}

/** 链接卡片（服务端解析 OG 后的结果） */
class LinkCard {
    String url;
    String title;
    String description;
    String image;
    String siteName;
    String favicon;
    String kind;      // article | video | image | wechat | github | doc | web
    boolean ok;
    String error;
}

/** 账号 */
class User {
    long id;
    String email;
    String nick;
    String avatar;
    String bio;
    long createdAt;
    long lastLogin;
    int postCount;
}

/** 登录 / 注册返回 */
class AuthResult {
    String token;
    User user;
}

/** 硬件行情型号 */
class HwModel {
    String key;
    String cat;
    String catLabel;
    String brand;
    String model;
    String spec;
    String keyword;
    String platform;
    String shop;
    String title;
    String trend;          // up | down | flat
    String priceStatus;    // ok | nomatch
    Object price;         // Double 或 null
    Object prevPrice;     // Double 或 null
    double changePct;     // 涨跌幅（%）
    double changeAbs;     // 实际差价 = 现价 - 上轮价
    boolean hasChange;
    long prevRoundAt;     // 上轮采集时间（ms）
    java.util.List<Double> trends;
    boolean noMatch;
    long updatedAt;
}

/** 行情品类 + 该品类型号数 */
class HwCat {
    String cat;
    int count;
}

/** 单型号价格走势的一个点 */
class HwPoint {
    long ts;
    double ref;
    double lo;
    double hi;
}

/** 单型号历史（点 + 统计） */
class HwHistory {
    java.util.List<HwPoint> points;
    double min, max, avg, last, change, changePct;
    int count;
    // 上轮对比
    double prevPrice, prevDiff, prevPct;
    long prevAt;
    boolean hasPrev;
}
