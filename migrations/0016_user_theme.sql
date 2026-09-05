-- 用户界面主题偏好（账号级，登录后随页面服务端直出，网页/App/换设备一致）
-- 取值: 'light'(默认浅色) | 'dark'(暗色) | 'eye'(护眼)
-- 执行: wrangler d1 execute cron_db --remote --file=migrations/0016_user_theme.sql
-- 本地: wrangler d1 execute cron_db --local --file=migrations/0016_user_theme.sql
-- 注意: D1 不支持 ADD COLUMN IF NOT EXISTS, 若列已存在重跑会报错(可忽略该条错误)
-- 注释必须独立成行, 兼容 D1 控制台逐条执行

ALTER TABLE users ADD COLUMN theme TEXT NOT NULL DEFAULT 'light';
