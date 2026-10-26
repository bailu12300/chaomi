# 早八 zaoba · 硬件行情 + 图文社区

一个自部署的数码硬件价格追踪与图文社区系统。**单个仓库、单个后端、三个前端**，
不做多项目拆分。

- 行情：显卡 / 处理器 / 固态硬盘 / 内存条 / 笔记本，采集轮次、价格走势、涨跌榜
- 社区：文字 / 图片 / 链接 / 公众号图文四类帖子，评论、点赞、WebSocket 实时
- 比价：型号详情页可一键把「当前价 + 渠道」转成一条讨论帖

---

## 仓库结构

| 目录 | 说明 | 技术栈 |
|---|---|---|
| `server/` | 唯一的后端：行情聚合 + 社区 + 实时推送 + 静态站点 | Go 1.23、MySQL 8 |
| `web/` | 社区 PWA（信息流 + 行情 + 走势图） | 原生 JS、无框架 |
| `mobile/` | 原生客户端 App：社区 + 行情 + 比价，含价格走势图 | Java 17、AndroidX、Material |
| `app/` | 早八采集 App：按型号抓取各平台价格并上报 | Kotlin、Jetpack Compose |
| `deploy/` | 部署脚本（凭据全部走环境变量） | Python + paramiko |
| `docs/` | 架构说明、接口清单、运维手册 | Markdown |

三个前端共用同一个域名与同一份数据：

```
采集 App(app/) ──上报──▶ server/ ──▶ MySQL ──▶ /api/hw/*
                                          └──▶ /api/community/*
web/ + mobile/ ◀──────REST + WebSocket────┘
```

---

## 快速开始

### 后端

```bash
cd server
export CM_DB_DSN='user:pass@tcp(127.0.0.1:3306)/cm_pricesite?charset=utf8mb4&timeout=5s'
go build -o cmpricesite .
./cmpricesite serve          # 默认监听 39581
```

> 数据库连接串**只从环境变量读取**，源码里没有任何凭据。

### 网页版

`web/` 是纯静态资源，后端启动后可直接访问：

```
https://<你的域名>/community
```

### 客户端 App

```bash
cd mobile
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleRelease
# 签名后放到 server 的 site/downloads/，/app 页面会自动指向最新 APK
```

`mobile/` 需自行配置 SDK 路径；签名 keystore 不入库。

---

## 接口一览

### 行情

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/hw/status` | 型号数、采集轮次、更新时间 |
| GET | `/api/hw/cats` | 品类列表（已归一化，带数量） |
| GET | `/api/hw/models?cat=&q=&sort=&limit=` | 行情列表，`sort` 支持 `price_asc`/`price_desc`/`change_desc`/`change_asc` |
| GET | `/api/hw/history/<key>` | 单型号价格走势 + 极值统计 |
| GET | `/api/hw/movers?window=1d\|7d\|30d&cat=` | 涨跌榜 |
| GET | `/api/hw/spark/<key>` | 精简走势数组（列表页 sparkline） |

`key` 不是数据库主键，而是**稳定身份**（品类+品牌+型号+规格，hex 编码）。
采集端每一轮生成的 `row_key` 会变，不能用来串时间轴。

### 社区

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/community/feed?before=` | 信息流分页 |
| POST | `/api/community/posts` | 发帖（text/image/link/article） |
| GET | `/api/community/post/<id>` | 帖子详情 + 评论 |
| POST | `/api/community/post/<id>/comments` | 评论 |
| POST | `/api/community/post/<id>/like` | 点赞 |
| POST | `/api/community/upload` | 图片上传（multipart，≤20MB） |
| GET | `/api/article/parse?url=` | 公众号图文解析 |
| WS | `/ws/community` | 实时事件：new_post / like / new_comment |

---

## 数据可信度约定

价格宁可不显示，也不显示错的：

1. 品类区间校验（`CAT_RANGE`）
2. 型号精准匹配（`RTX 4060` 不会命中 `4060 Ti`）
3. 商品有效性过滤（二手、拆机、整机、板U套装、配件全部剔除）
4. 多型号打包检测（标题含多个核心型号即判为打包链接）
5. 平台优先级（京东自营 > 京东 > 天猫 > 淘宝 > 其它）
6. **单轮涨跌幅超过 ±40% 视为脏数据**，接口直接不返回

不满足条件时 `price=null`、`priceStatus=nomatch`，前端显示「暂无准确报价」。

---

## 采集规则（硬约束）

| 项 | 值 |
|---|---|
| 节流 | 3 分钟/型号 |
| 轮次 | 12 小时一轮 |
| 保护期 | 每轮最后 1 小时禁止抓取 |
| 历史保留 | 早八 7 天，更长历史由 `server/` 落 MySQL |

采集在 App 端（手机 IP）完成，服务器只存数据、不参与抓取。

---

## 开发约定

- 后端单进程，无外部缓存依赖；新增接口直接注册在 `doServe()` 的 mux 上
- 品类中文名必须与 `hwCatOrder` 一致：显卡 / 处理器 / 固态硬盘 / 内存条 / 笔记本
- 写库改动部署前必须交叉编译通过：`GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build`
- **任何提交前跑一次凭据扫描**：`grep -rniE "password|secret|token|ghp_|AKIA" .`；
  `*.env` / `db.conf` / `*.keystore` 一律不入库

---

## 下载

最新客户端：`https://<你的域名>/app`（自动指向 `site/downloads/` 里最新的 APK）

## License

私有项目，未授权请勿分发。
