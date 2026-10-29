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
    String accountName;     // 公众号名称
    String accountAvatar;   // 公众号头像
    long publishTime;       // 原文发布时间
    int likes;
    boolean liked;
    int views;
    int commentCount;
    String ipLoc;      // IP 归属地（省市，如「广东」）
    long createdAt;
}

/** 评论 */
class Comment {
    long id;
    long postId;
    long userId;
    String author;
    String body;
    String ipLoc;     // IP 归属地
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
    String type;   // p | h2 | h3 | img | quote | code | li | ol | video | hr | topic | link
    String text;
    String src;
    String alt;    // img 图注 / video 封面
    String style;  // 对齐等附加样式：center | （空=默认左对齐）
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
    // 公众号公开信息
    String accountName;
    String accountId;
    String accountAvatar;
    long publishTime;
    String publishText;
    int wordCount;
    int imageCount;
    String firstImage;
    String kind;
    List<Block> blocks;
    int count;
}

/** 显卡行情：零售卡（Amazon）与云 GPU（VoltageGPU）统一模型 */
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
    String uid;       // 唯一号 CM123456
    String email;
    String nick;
    String avatar;
    String bio;
    long createdAt;
    long lastLogin;
    int postCount;
    String regIp;
    String lastIp;
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

/** RSS 资讯条目（服务端已拉取并翻译） */
class RssItem {
    long id;
    String category;
    String title;
    String titleZh;     // 中文译文（离线可用）
    String link;
    String summary;
    String summaryZh;
    String source;
    String image;
    String lang;
    long pubAt;
    long fetchedAt;

    /** 展示用标题：优先译文 */
    String displayTitle() {
        return (titleZh != null && !titleZh.isEmpty()) ? titleZh : (title == null ? "" : title);
    }

    String displaySummary() {
        return (summaryZh != null && !summaryZh.isEmpty()) ? summaryZh : (summary == null ? "" : summary);
    }
}

/** RSS 分类（一级菜单下的二级分类） */
class RssCat {
    String key;
    String name;
    int count;
}

/** 天气当前实况 */
class WeatherNow {
    double tempC;
    double feelsC;
    int humidity;
    double windKph;
    int windDir;
    int code;
    String text;
    String icon;
    boolean isDay;
    double precipMm;
    double pressure;
    double uvIndex;
}

/** 某一天的预报 */
class WeatherDay {
    String date;
    double maxC;
    double minC;
    int code;
    String text;
    String icon;
    double precipMm;
    int precipProb;
}

/** 天气卡片整体数据 */
class WeatherCard {
    String city;
    String region;
    double lat;
    double lon;
    WeatherNow now;
    List<WeatherDay> days;
    long updatedAt;
    String source;
}

/** 服务监控：单个目标的当前状态（对应 /api/watch/status 的 list 项） */
class WatchResult {
    String key;
    String name;
    boolean ok;
    int status;
    long latencyMs;
    String error;
    int failStreak;
    long checkedAt;
}

/** 服务监控总览 */
class WatchStatus {
    List<WatchResult> list;
    int up;
    int down;
    int interval;
}

/** 短视频去水印解析结果 */
class WmResult {
    String platform;
    String platformName;
    String title;
    String author;
    String cover;
    int duration;        // 秒
    String videoUrl;     // 无水印直链
    String originalUrl;
    java.util.List<String> images;
    String note;         // 提示 / 不支持的原因
    boolean ok;
}

/** 格式转换能力清单 */
class ConvertCaps {
    java.util.List<String> dataFrom;
    java.util.List<String> dataTo;
    java.util.List<java.util.Map<String, String>> codec;
}

