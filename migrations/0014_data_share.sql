-- 数据共享（家庭/团队）：主人生成邀请码并勾选模块，家人凭码加入后，
-- 在被授权模块内读写主人那一套数据（数据不搬家，仍归主人；权限实时跟随邀请的 modules）。
-- 每条注释独立成行，D1 控制台逐条执行。

-- 分享邀请：一个邀请 = 一个短码 + 一组模块（逗号分隔，如 asset,weight,todo）。
-- 重置码 = 更换 code（旧码立即失效，已加入成员关系保留）；撤销 = revoked_at 置位并踢掉全部成员。
CREATE TABLE IF NOT EXISTS share_invites (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  code TEXT NOT NULL UNIQUE,
  owner_user_id INTEGER NOT NULL,
  modules TEXT NOT NULL,
  note TEXT,
  revoked_at TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_share_invites_owner ON share_invites(owner_user_id);
CREATE INDEX IF NOT EXISTS idx_share_invites_code ON share_invites(code);

-- 分享成员：家人凭码加入后产生一行；UNIQUE(invite_id, guest) 保证幂等（退出后再加入即复活）。
-- revoked_at 非空 = 已退出或被主人移出；权限是否有效还要同时看其邀请的 revoked_at。
CREATE TABLE IF NOT EXISTS share_members (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  invite_id INTEGER NOT NULL,
  owner_user_id INTEGER NOT NULL,
  guest_user_id INTEGER NOT NULL,
  role TEXT NOT NULL DEFAULT 'editor',
  revoked_at TEXT,
  joined_at TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE(invite_id, guest_user_id)
);
CREATE INDEX IF NOT EXISTS idx_share_members_guest ON share_members(guest_user_id);
CREATE INDEX IF NOT EXISTS idx_share_members_owner ON share_members(owner_user_id);
