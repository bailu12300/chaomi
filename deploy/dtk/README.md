# DTK v5 部署（抖音/TikTok 去水印引擎）

去水印功能依赖本地 DTK v5（Evil0ctal/Douyin_TikTok_Download_API，Apache-2.0）。
它用 CloakBrowser 无头浏览器**自动铸造游客身份**，所以抖音不需要用户登录。

## 为什么单独部署

抖音的 a_bogus / msToken 签名由平台自己的 JS 计算，纯 HTTP 拿不到。
DTK v5 在容器里跑真实 Chromium，让平台自己算签名，因此免登录可用。

## 步骤

```bash
git clone --depth 1 https://github.com/Evil0ctal/Douyin_TikTok_Download_API.git /opt/dtk5
cd /opt/dtk5

# 生成 .env（密码自己随机生成，不要提交）
POSTGRES_PASSWORD=$(openssl rand -hex 24)
REDIS_PASSWORD=$(openssl rand -hex 24)
cat > .env <<EOF
DTK_SECRET_KEY=$(openssl rand -base64 48)
POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
REDIS_PASSWORD=${REDIS_PASSWORD}
DTK_DATABASE_URL=postgresql+asyncpg://dtk:${POSTGRES_PASSWORD}@postgres:5432/dtk
DTK_REDIS_URL=redis://:${REDIS_PASSWORD}@redis:6379/0
DTK_REDIS_MAXMEMORY=96mb
DTK_BIND_HOST=127.0.0.1
DTK_BIND_PORT=18000
DTK_BROWSER_RPC_URL=http://browser-rpc:9000
DTK_BROWSER_BACKEND=cloak
DTK_BROWSER_WARM_CONTEXTS=1
DTK_BROWSER_MAX_CONCURRENT_MINTS=1
EOF

# 复制 compose 覆盖（小内存适配）
cp /path/to/deploy/dtk/compose.override.yml docker/

export DTK_IMAGE=evil0ctal/douyin_tiktok_download_api
export DTK_IMAGE_TAG=5.1.8
export COMPOSE_ENV_FILES=.env

docker compose -p dtk -f docker/compose.yml -f docker/compose.override.yml up -d
docker compose -p dtk -f docker/compose.yml -f docker/compose.override.yml     --profile browser up -d browser-rpc
```

## 初始化

1. `docker logs dtk-api-1 | grep setup?token` 拿 setup token
2. `POST /api/setup/init {"token","username","password"}` 创建管理员
3. 登录拿 cookie，`POST /api/v1/admin/api-keys` 建 key
   （scopes 必须是具体值：`douyin:read,tiktok:read,bilibili:read,admin`，不支持 `*`）
4. 把 key 写进主站 `.env` 的 `DTK_API_KEY`

## 主站侧配置

主站 `server/watermark.go` 通过 `DTK_API_KEY` 环境变量读取 key：

```
DTK_API_KEY=dtk_xxxxxxxx_yyyyyyy
```

## 接口

- `POST /api/v1/parse {"url": "..."}` → 202 + task_id
- `GET /api/v1/tasks/{id}` → state=done/failed + data
- 鉴权：`X-API-Key`

抖音 `content_detail` 的 `login=none`，所以游客身份即可解析。
