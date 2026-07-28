# ---------- deps 阶段 ----------
# node:20-slim (Debian) 而非 alpine：miniflare 传递依赖 workerd 使用 glibc，musl 会启动失败
FROM node:20-slim AS deps
WORKDIR /app
COPY package.json ./
# 只装运行本地部署所需的 miniflare；不装 wrangler（大且没用）
RUN npm install --omit=dev miniflare@3

# ---------- runtime 阶段 ----------
FROM node:20-slim
WORKDIR /app

# 复制依赖
COPY --from=deps /app/node_modules ./node_modules
COPY --from=deps /app/package.json ./package.json

# 复制业务源码 + 迁移 + 宿主脚本
COPY src ./src
COPY migrations ./migrations
COPY docker ./docker

# 数据目录（挂 volume 到此以便持久化）
ENV DATA_DIR=/data \
    PORT=8787 \
    HOST=0.0.0.0 \
    STORAGE_DRIVER=d1
RUN mkdir -p /data && chown -R node:node /data /app
VOLUME ["/data"]

USER node
EXPOSE 8787

CMD ["node", "docker/server.mjs"]
