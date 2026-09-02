/**
 * 数据共享 API（家庭/团队）
 *
 * 数据不搬家、仍归主人：主人生成「邀请码 + 模块集」，家人凭码加入后，
 * 在被授权模块内读写主人那一套数据。各业务模块通过 requireDataContext
 * 解析本次请求应操作谁的数据（请求头 X-Data-As 指定主人 uid）。
 *
 * monitor（定时任务）属个人自动化、不纳入共享；可共享模块见 SHARE_MODULES。
 */

import { json, error } from '../router.js';
import { getStorage } from '../storage/adapter.js';
import { requireAuth } from '../auth/middleware.js';
import { resolveBaseUrl } from '../config.js';

// 可共享的业务模块（monitor 个人自动化不纳入）
const SHARE_MODULES = ['fund', 'weight', 'asset', 'todo'];

// 短邀请码字母表：去掉易混淆的 0/O、1/I/l
const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789';

/** 生成 8 位短邀请码（webcrypto，Workers / Node 18+ 均可用） */
function genInviteCode(len = 8) {
  const bytes = crypto.getRandomValues(new Uint8Array(len));
  let s = '';
  for (const b of bytes) s += CODE_ALPHABET[b % CODE_ALPHABET.length];
  return s;
}

/** 生成一个不与现存有效邀请冲突的短码 */
async function uniqueCode(storage) {
  for (let i = 0; i < 5; i++) {
    const code = genInviteCode();
    const exists = await storage.share.findInviteByCode(code);
    if (!exists) return code;
  }
  return genInviteCode(12); // 极小概率冲突兜底：加长再试一次
}

/**
 * 解析本次请求在某模块上「实际操作谁的数据」。
 * 请求头 X-Data-As: <主人uid> 表示切换到该主人的共享数据源；不带或为自己 → 操作本人数据。
 * @returns {Promise<{uid:number, shared:boolean, owner:Object|null}|Response>}
 *          无授权时返回 403 Response（调用方用 instanceof Response 判断并 return）。
 */
async function requireDataContext(storage, auth, module, request) {
  const self = { uid: auth.user_id, shared: false, owner: null };
  const target = parseInt(request.headers.get('X-Data-As') || '', 10);
  if (!target || target === auth.user_id) return self;
  const share = await storage.share.findActiveShare(auth.user_id, target);
  const mods = share ? (share.modules || '').split(',').map(s => s.trim()) : [];
  if (!share || !mods.includes(module)) return error('无权访问该共享数据', 403);
  const owner = await storage.users.findById(target);
  return {
    uid: target,
    shared: true,
    owner: owner ? { id: owner.id, nickname: owner.nickname || owner.username } : null
  };
}

// ==================== 邀请（主人）====================

/** POST /api/share/invites  创建邀请  body: { modules:[...], note? } */
async function createInvite({ request, env, url }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const body = await request.json().catch(() => ({}));
  const modules = [...new Set(
    (Array.isArray(body.modules) ? body.modules : []).filter(m => SHARE_MODULES.includes(m))
  )];
  if (modules.length === 0) return error('请至少选择一个共享模块');
  const storage = getStorage(env);
  const code = await uniqueCode(storage);
  const id = await storage.share.createInvite(auth.user_id, {
    code, modules: modules.join(','), note: (body.note || '').trim() || null
  });
  const base = await resolveBaseUrl(storage, env, url);
  return json({
    success: true, id, code, modules,
    link: `${base}/settings?join=${code}`
  });
}

/** GET /api/share/invites  我发起的邀请（含成员列表） */
async function listInvites({ request, env }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const storage = getStorage(env);
  const invites = await storage.share.listInvitesByOwner(auth.user_id);
  const result = [];
  for (const inv of invites) {
    const members = await storage.share.listMembersByInvite(inv.id);
    result.push({
      id: inv.id,
      code: inv.code,
      modules: (inv.modules || '').split(','),
      note: inv.note,
      revoked: !!inv.revoked_at,
      created_at: inv.created_at,
      members: members.map(m => ({
        id: m.id,
        username: m.username,
        nickname: m.nickname || m.username,
        joined_at: m.joined_at,
        active: !m.revoked_at
      }))
    });
  }
  return json({ success: true, invites: result });
}

/** POST /api/share/invites/:id/reset  重置邀请码（旧码失效，成员保留） */
async function resetInvite({ request, env, params }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const storage = getStorage(env);
  const id = parseInt(params.id, 10);
  const inv = await storage.share.findInviteById(id);
  if (!inv || inv.owner_user_id !== auth.user_id) return error('邀请不存在', 404);
  const code = await uniqueCode(storage);
  await storage.share.updateInviteCode(id, code);
  return json({ success: true, code, message: '邀请码已重置，旧码立即失效' });
}

/** POST /api/share/invites/:id/revoke  撤销邀请（旧码失效并踢出全部成员） */
async function revokeInvite({ request, env, params }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const storage = getStorage(env);
  const id = parseInt(params.id, 10);
  const inv = await storage.share.findInviteById(id);
  if (!inv || inv.owner_user_id !== auth.user_id) return error('邀请不存在', 404);
  await storage.share.revokeInvite(id);
  await storage.share.revokeMembersByInvite(id);
  return json({ success: true, message: '邀请已撤销，全部成员已移出' });
}

// ==================== 加入 / 退出（家人）====================

/** POST /api/share/join  凭码加入  body: { code } */
async function joinInvite({ request, env }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const body = await request.json().catch(() => ({}));
  const code = (body.code || '').trim();
  if (!code) return error('请输入共享码');
  const storage = getStorage(env);
  const inv = await storage.share.findInviteByCode(code);
  if (!inv) return error('共享码无效或已失效');
  if (inv.owner_user_id === auth.user_id) return error('这是你自己创建的共享码，无需加入');
  await storage.share.addMember({
    invite_id: inv.id, owner_user_id: inv.owner_user_id, guest_user_id: auth.user_id
  });
  const owner = await storage.users.findById(inv.owner_user_id);
  return json({
    success: true,
    message: `已加入「${owner ? (owner.nickname || owner.username) : ''}」的共享，可在对应模块顶部切换数据源`,
    modules: (inv.modules || '').split(',')
  });
}

/** GET /api/share/mine  我加入的共享（可切换的数据源） */
async function listMyShares({ request, env }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const storage = getStorage(env);
  const rows = await storage.share.listMyShares(auth.user_id);
  const shares = rows.map(r => ({
    member_id: r.member_id,
    invite_id: r.invite_id,
    owner_user_id: r.owner_user_id,
    owner_name: r.owner_nickname || r.owner_username,
    modules: (r.modules || '').split(','),
    note: r.note,
    joined_at: r.joined_at
  }));
  return json({ success: true, shares });
}

/** DELETE /api/share/members/:id  主人踢人 / 自己退出（按关系归属判定） */
async function removeShareMember({ request, env, params }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const storage = getStorage(env);
  const id = parseInt(params.id, 10);
  const m = await storage.share.findMemberById(id);
  if (!m) return error('共享关系不存在', 404);
  if (m.owner_user_id !== auth.user_id && m.guest_user_id !== auth.user_id) {
    return error('无权操作该共享关系', 403);
  }
  await storage.share.revokeMember(id);
  return json({
    success: true,
    message: m.guest_user_id === auth.user_id ? '已退出共享' : '已移出该成员'
  });
}

export {
  SHARE_MODULES,
  requireDataContext,
  createInvite, listInvites, resetInvite, revokeInvite,
  joinInvite, listMyShares, removeShareMember
};
