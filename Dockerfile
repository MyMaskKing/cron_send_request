# ---------- deps 阶段 ----------
# node:20-alpine 极简；better-sqlite3 需要 native 编译，故加临时编译工具链
FROM node:20-alpine AS deps
WORKDIR /app
RUN apk add --no-cache python3 make g++
COPY package.json ./
# 只装运行时需要的 better-sqlite3；不装 wrangler、miniflare 等开发依赖
RUN npm install --omit=dev better-sqlite3@11

# ---------- runtime 阶段 ----------
FROM node:20-alpine
WORKDIR /app

# 复制依赖（node_modules 中已含 better-sqlite3 的预编译 .node 二进制）
COPY --from=deps /app/node_modules ./node_modules
COPY --from=deps /app/package.json ./package.json

# 复制业务源码 + 迁移 + 宿主脚本
COPY src ./src
COPY migrations ./migrations
COPY docker ./docker

ENV DATA_DIR=/data \
    PORT=8787 \
    HOST=0.0.0.0 \
    STORAGE_DRIVER=d1

RUN mkdir -p /data && chown -R node:node /data /app
VOLUME ["/data"]

USER node
EXPOSE 8787

CMD ["node", "docker/server.mjs"]
