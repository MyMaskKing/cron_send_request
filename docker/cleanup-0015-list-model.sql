-- 清理初版 0015（共享目录错误模型）残留，仅在已经跑过 0015_todo_shared_list.sql 的库上执行。
-- 执行后重启容器，0015_todo_shared_cat.sql 会作为新迁移自动应用。
-- 线上 D1 未执行过初版 0015，无需跑本脚本。
-- 注意：所有语句独立成行，兼容 D1 控制台逐条执行。

-- 1. 删除引用 list_id 列的索引（DROP COLUMN 前必须先删，否则 SQLite 拒绝）
DROP INDEX IF EXISTS idx_todos_list;

-- 2. 删除初版两张表
DROP TABLE IF EXISTS todo_list_members;
DROP TABLE IF EXISTS todo_list_invites;

-- 3. 删除初版加在 todos 上的 list_id 列
--    （created_by / done_by 两列新模型复用，保留不删；新迁移再次 ADD 时会报
--      duplicate column，Docker 迁移执行器与 D1 均可忽略该错误）
ALTER TABLE todos DROP COLUMN list_id;

-- 4. 删除初版迁移记录（按文件名记录），避免后续排查时混淆
DELETE FROM _migrations WHERE name = '0015_todo_shared_list.sql';
