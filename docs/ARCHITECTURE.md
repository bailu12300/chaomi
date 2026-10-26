# 架构说明

## 一句话

单个 Go 进程同时承担「行情聚合 + 社区 + 实时推送 + 静态站点」，前端三端（PWA / 安卓 / 采集 App）全部对接它。

## 进程与端口

```
nginx :443 ──反代──▶ 127.0.0.1:39581 (cmpricesite serve, 明文 HTTP)
```

- nginx 终止 TLS；后端只监听回环，不直接暴露公网
- 后端是**独立进程**（`setsid ./bin/cmpricesite serve`），不是 systemd 服务
- 重启方式：按 `ss -ltnp | grep 39581` 取出真实监听 pid 后 kill，再 `setsid` 拉起

> 直接 `pkill -f 'bin/cmpricesite serve'` 会命中启动它的 shell，导致自杀式重启失败。

## 数据表

| 表 | 作用 | 关键列 |
|---|---|---|
| `jd_latest` | 当前价（每个型号一行） | `row_key`、`cat_label`、`brand`、`model`、`spec`、`price`、`prev_price`、`trend`、`ts` |
| `jd_rounds` | 每轮采集明细（只增） | 同上 + `reused` |
| `price_history` | 长历史序列 | `module`、`row_key`、`ts`、`ref_price`、`lo_price`、`hi_price` |
| `c_posts` / `c_comments` | 社区帖子 / 评论 | `id`、`type`、`title`、`body`、`images`、`likes` |

### 为什么需要「稳定身份」

采集端每一轮生成的 `row_key` 会变（早期是 `gpu-rtx-50` 这类系列键，后期变成内容哈希），
所以**不能用 `row_key` 串时间轴**。行情接口统一使用：

```
stableKey = hex( 归一化品类 | 品牌 | 型号 | 规格归一化 )
```

`/api/hw/history/<stableKey>` 再反解出四元组，同时查 `jd_rounds`（轮次价）
和 `price_history`（历史序列），按 `ts` 去重合并。

## 采集与导入链路

```
采集 App(app/) ──POST 价格──▶ server/ /internal/... ──▶ jd_latest + jd_rounds
                                                     └─▶ price_history（同轮去重）
```

导入时每写入一条 `jd_latest`，同时向 `price_history` 落一条点。
`price_history` 有唯一索引 `uk(module,row_key,ts)`，重复导入天然幂等。

## 前端

### PWA（`web/`）

- 原生 JS，无构建步骤；Service Worker 缓存应用壳，`/api/*` 与 `/ws/*` 永远走网络
- 行情页：品类 chips + 搜索 + 排序 + 涨跌榜；点击卡片弹出详情，走势图用原生 SVG 绘制

### 安卓客户端（`mobile/`）

- Java + AndroidX + Material，`minSdk 26`
- 信息流用 `RecyclerView` + `SwipeRefreshLayout`
- 实时用 `Java-WebSocket`，收到 `new_post` 直接插入列表
- 走势图是自绘 `TrendChartView`（Canvas 折线 + 渐变填充），不引图表库以控制体积
- 发布用 `BottomSheetDialog`，支持四类内容；图片走 `multipart/form-data` 上传

## 安全

- 所有凭据（DB DSN、SSH、crawler key）只在服务器 `*.env` / `db.conf`，不进仓库
- 仓库内 `deploy/*.py` 只读环境变量 `ZAOBA_HOST` / `ZAOBA_USER` / `ZAOBA_PASS`
- 提交前必跑凭据扫描，`*.env`、`db.conf`、`*.keystore` 已在 `.gitignore` 里

## 已知限制

- 采集源是慢慢买，客观短板：CPU 多为板U套装、显卡存在多型号打包链接
- 因此常有型号返回 `price=null`，这是**预期行为**而非故障
- 涨跌榜依赖多轮数据，冷启动阶段列表可能为空
