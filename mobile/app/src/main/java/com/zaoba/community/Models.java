package com.zaoba.community;

import java.util.List;

/** 社区帖子 */
class Post {
    long id;
    String type;       // text | image | link | article
    String title;
    String body;
    String cover;
    String url;
    List<String> images;
    String author;
    List<String> tags;
    int likes;
    int commentCount;
    long createdAt;
}

/** 评论 */
class Comment {
    long id;
    long postId;
    String author;
    String body;
    long createdAt;
}

/** 帖子详情 = 帖子 + 评论列表 */
class PostAndComments {
    Post post;
    List<Comment> comments;
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
    boolean hasChange;
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
}
