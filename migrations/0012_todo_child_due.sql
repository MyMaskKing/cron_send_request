-- Todo 子任务独立截止日期模式：主任务新增 child_due 标志
-- 执行: wrangler d1 execute cron_db --remote --file=migrations/0012_todo_child_due.sql
-- 本地: wrangler d1 execute cron_db --local --file=migrations/0012_todo_child_due.sql
-- 注意: D1 不支持 ADD COLUMN IF NOT EXISTS, 若列已存在重跑会报错(可忽略该条错误)
-- 注释必须独立成行, 兼容 D1 控制台逐条执行

-- child_due: 1=子任务独立截止日期模式
-- 该模式下主任务自身不设 due_date/recurrence; 子任务可各自设 due_date, 叶子子任务可设重复
-- 0=旧模式(默认, 与历史数据兼容): 日期/重复只在顶层主任务上, 子任务继承主任务日期
-- 仅顶层任务(parent_id 为空)此列有意义; 由业务代码保证
ALTER TABLE todos ADD COLUMN child_due INTEGER NOT NULL DEFAULT 0;
