-- Todo 共享分类：主人创建共享分类并生成邀请码，家人凭码加入后，
-- 该分类下的所有任务对成员可见可协作（数据不搬家，任务 user_id 恒为分类 owner；
-- 真实操作人记 created_by/done_by）。共享维度是 category 分类，不是任务树。
-- 执行: wrangler d1 execute cron_db --remote --file=migrations/0015_todo_shared_list.sql
-- 本地: wrangler d1 execute cron_db --local --file=migrations/0015_todo_shared_list.sql
-- 注意: D1 不支持 ADD COLUMN IF NOT EXISTS, 若列已存在重跑会报错(可忽略该条错误)
-- 注释必须独立成行, 兼容 D1 控制台逐条执行

-- shared_cat_id: NULL=个人任务; 非空=所属共享分类 id(todo_shared_cats.id)
-- 同一分类下的任务(含其子任务树)同值; 分类解散时置 NULL, 任务保留在 owner 名下
ALTER TABLE todos ADD COLUMN shared_cat_id INTEGER;

-- created_by: 任务实际创建者 uid; 个人任务=user_id, 共享分类内=真实操作人(免密匿名添加为 NULL)
ALTER TABLE todos ADD COLUMN created_by INTEGER;

-- done_by: 最后一次勾选完成的操作人 uid; 取消完成时置 NULL
ALTER TABLE todos ADD COLUMN done_by INTEGER;

-- 按共享分类查询的索引(可见性 UNION/成员校验用)
CREATE INDEX IF NOT EXISTS idx_todos_shared_cat ON todos(shared_cat_id);

-- 共享分类: 一个分类一行, 邀请码 code 并入本表(一分类一码, 重置=换 code)
CREATE TABLE IF NOT EXISTS todo_shared_cats (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  owner_user_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  code TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_tsc_owner ON todo_shared_cats(owner_user_id);
CREATE INDEX IF NOT EXISTS idx_tsc_code ON todo_shared_cats(code);

-- 共享分类成员: role 为 owner(创建者, 唯一) 或 editor(默认)
-- 物理删除模型(同 share_members): 退出/踢人=DELETE 行, 重新加入 INSERT OR IGNORE 即恢复
CREATE TABLE IF NOT EXISTS todo_shared_cat_members (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  cat_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  role TEXT NOT NULL DEFAULT 'editor',
  joined_at TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE(cat_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_tscm_user ON todo_shared_cat_members(user_id);
CREATE INDEX IF NOT EXISTS idx_tscm_cat ON todo_shared_cat_members(cat_id);
