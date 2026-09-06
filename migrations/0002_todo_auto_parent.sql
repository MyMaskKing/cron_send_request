-- 0002: 待办偏好——子任务全部完成后父任务自动完成
-- todo_auto_parent: 1=开启(默认), 0=关闭(关闭后勾选子任务不影响父任务状态)
ALTER TABLE users ADD COLUMN todo_auto_parent INTEGER NOT NULL DEFAULT 1;
