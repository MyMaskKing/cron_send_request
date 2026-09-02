-- 体重成员管理增强：排序、禁用、归档（软删）
-- 每个新增列的注释独立成行，D1 控制台逐条执行。

-- sort_order: 成员排序权重，越小越靠前；新建成员自动取当前最大值 + 1
ALTER TABLE weight_members ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;

-- disabled: 1=暂时停用。曲线/录入下拉/日报推送均隐藏，但历史数据全部保留，可随时重新启用
ALTER TABLE weight_members ADD COLUMN disabled INTEGER NOT NULL DEFAULT 0;

-- archived: 1=成员已移除（软删）。界面完全不显示，但体重记录保留；
-- 新建同名成员时检测到归档成员可恢复（archived 置 0），历史记录自动重新关联
ALTER TABLE weight_members ADD COLUMN archived INTEGER NOT NULL DEFAULT 0;
