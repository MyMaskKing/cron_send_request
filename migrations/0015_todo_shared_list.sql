-- Todo 共享目录：登录用户凭邀请码加入目录后多人协作（数据不搬家，仍归目录创建者）
-- 执行: wrangler d1 execute cron_db --remote --file=migrations/0015_todo_shared_list.sql
-- 本地: wrangler d1 execute cron_db --local --file=migrations/0015_todo_shared_list.sql
-- 注意: D1 不支持 ADD COLUMN IF NOT EXISTS, 若列已存在重跑会报错(可忽略该条错误)
-- 注释必须独立成行, 兼容 D1 控制台逐条执行

-- list_id: NULL=个人任务; 非空=所属共享目录的根任务 id
-- 共享目录的根任务自引用(list_id = 自身 id), 其整棵子树同值
ALTER TABLE todos ADD COLUMN list_id INTEGER;

-- created_by: 任务实际创建者 uid; 个人任务=user_id, 共享目录内=真实操作人(免密匿名添加为 NULL)
ALTER TABLE todos ADD COLUMN created_by INTEGER;

-- done_by: 最后一次勾选完成的操作人 uid; 取消完成时置 NULL
ALTER TABLE todos ADD COLUMN done_by INTEGER;

-- 按目录查询的索引(可见性 UNION/成员校验用)
CREATE INDEX IF NOT EXISTS idx_todos_list ON todos(list_id);

-- 共享目录成员: list_id = 根任务 id; role 为 owner(创建者, 唯一) 或 editor(默认)
-- 物理删除模型(同 share_members): 退出/踢人=DELETE 行, 重新加入 INSERT OR IGNORE 即恢复
CREATE TABLE IF NOT EXISTS todo_list_members (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  list_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  role TEXT NOT NULL DEFAULT 'editor',
  joined_at TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE(list_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_tlm_user ON todo_list_members(user_id);
CREATE INDEX IF NOT EXISTS idx_tlm_list ON todo_list_members(list_id);

-- 目录邀请码: 一个目录一条有效邀请(UNIQUE list_id); 重置码=更换 code, 目录删除时级联清理
CREATE TABLE IF NOT EXISTS todo_list_invites (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  list_id INTEGER NOT NULL UNIQUE,
  code TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_tli_code ON todo_list_invites(code);
