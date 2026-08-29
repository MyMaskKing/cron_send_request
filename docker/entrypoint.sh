#!/bin/sh
# 容器以 root 启动：先修正持久化目录属主，再降权到 node 用户运行主进程。
# 背景：bind mount 的 ./docker-data 若被删除，Docker 会以 root 重建该目录并覆盖
# 镜像内 chown node 的 /data，导致 USER node 无权创建 SQLite 文件
# （better-sqlite3 报 "unable to open database file"）。每次启动 chown 一次即可自愈。
set -e
chown -R node:node /data
exec su-exec node "$@"
