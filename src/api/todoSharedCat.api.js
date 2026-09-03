/**
 * 待办共享分类 API
 *
 * 共享维度是 category 分类：主人创建共享分类（如「家庭」）并生成邀请码，家人凭码
 * 加入后，该分类下的所有任务对成员可见可写。任务仍是各自独立的顶层任务（有自己的
 * 日期/子任务/重复），不存在"容器任务"。数据不搬家：任务 user_id 恒为分类 owner，
 * 真实操作人记 todos.created_by / done_by；退出/踢人任务保留，解散分类则任务摘掉
 * 分类标签变回 owner 的个人任务。
 */

import { json, error } from '../router.js';
import { getStorage } from '../storage/adapter.js';
import { requireAuth } from '../auth/middleware.js';
import { genInviteCode } from './share.api.js';
import { resolveBaseUrl } from '../config.js';

/** 生成不与现存分类邀请冲突的 8 位码（极小概率冲突兜底加长再试一次） */
async function uniqueCatCode(storage) {
  for (let i = 0; i < 5; i++) {
    const code = genInviteCode();
    const exists = await storage.sharedCat.findCatByCode(code);
    if (!exists) return code;
  }
  return genInviteCode(12);
}

/** 校验分类存在且当前用户是 owner；返回 { cat } 或 { error: Response } */
async function requireCatOwner(storage, auth, catId) {
  const cat = await storage.sharedCat.findCatById(catId);
  if (!cat) return { error: error('共享分类不存在', 404) };
  const m = await storage.sharedCat.findMember(catId, auth.user_id);
  if (!m || m.role !== 'owner') return { error: error('仅分类创建者可操作', 403) };
  return { cat };
}

/** POST /api/todo/shared-cats  创建共享分类  body: { name } */
async function createSharedCat({ request, env, url }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const body = await request.json().catch(() => ({}));
  const name = (body.name || '').trim();
  if (!name) return error('请填写分类名称');

  const storage = getStorage(env);
  const code = await uniqueCatCode(storage);
  const id = await storage.sharedCat.createCat(auth.user_id, name, code);
  const base = await resolveBaseUrl(storage, env, url);
  return json({ success: true, id, name, code, link: `${base}/todo?join=${code}`, message: '共享分类已创建' });
}

/** POST /api/todo/shared-cats/join  凭码加入  body: { code } */
async function joinSharedCat({ request, env }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const body = await request.json().catch(() => ({}));
  const code = (body.code || '').trim();
  if (!code) return error('请输入邀请码');
  const storage = getStorage(env);
  const cat = await storage.sharedCat.findCatByCode(code);
  if (!cat) return error('邀请码无效或已失效');
  if (cat.owner_user_id === auth.user_id) return error('这是你自己创建的分类，无需加入');
  await storage.sharedCat.addMember(cat.id, auth.user_id);
  return json({ success: true, cat_id: cat.id, name: cat.name, message: `已加入「${cat.name}」` });
}

/** GET /api/todo/shared-cats/mine  我参与的共享分类（owner 附带邀请码） */
async function mySharedCats({ request, env }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const storage = getStorage(env);
  const rows = await storage.sharedCat.listMyCats(auth.user_id);
  return json({
    success: true,
    cats: rows.map(c => ({
      cat_id: c.cat_id,
      name: c.cat_name,
      role: c.role,
      owner_name: c.owner_nickname || c.owner_username,
      member_count: c.member_count,
      joined_at: c.joined_at,
      code: c.role === 'owner' ? c.invite_code : null
    }))
  });
}

/** GET /api/todo/shared-cats/:id/invite  获取邀请码与链接（owner） */
async function getCatInvite({ request, env, params, url }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const storage = getStorage(env);
  const catId = parseInt(params.id, 10);
  const owned = await requireCatOwner(storage, auth, catId);
  if (owned.error) return owned.error;
  const base = await resolveBaseUrl(storage, env, url);
  return json({ success: true, code: owned.cat.code, link: `${base}/todo?join=${owned.cat.code}` });
}

/** POST /api/todo/shared-cats/:id/invite/reset  重置邀请码（旧码失效，成员保留） */
async function resetCatInvite({ request, env, params }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const storage = getStorage(env);
  const catId = parseInt(params.id, 10);
  const owned = await requireCatOwner(storage, auth, catId);
  if (owned.error) return owned.error;
  const code = await uniqueCatCode(storage);
  await storage.sharedCat.updateCatCode(catId, code);
  return json({ success: true, code, message: '邀请码已重置，旧码立即失效' });
}

/** GET /api/todo/shared-cats/:id/members  成员列表（分类成员） */
async function listCatMembers({ request, env, params }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const storage = getStorage(env);
  const catId = parseInt(params.id, 10);
  const me = await storage.sharedCat.findMember(catId, auth.user_id);
  if (!me) return error('你不是该分类成员', 403);
  const cat = await storage.sharedCat.findCatById(catId);
  if (!cat) return error('共享分类不存在', 404);
  const members = await storage.sharedCat.listMembers(catId);
  return json({
    success: true,
    name: cat.name,
    members: members.map(m => ({
      user_id: m.user_id,
      nickname: m.nickname || m.username,
      role: m.role,
      joined_at: m.joined_at
    }))
  });
}

/** POST /api/todo/shared-cats/:id/leave  退出分类（editor；owner 只能解散） */
async function leaveSharedCat({ request, env, params }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const storage = getStorage(env);
  const catId = parseInt(params.id, 10);
  const me = await storage.sharedCat.findMember(catId, auth.user_id);
  if (!me) return error('你不是该分类成员', 403);
  if (me.role === 'owner') return error('创建者不能退出，请直接解散分类', 400);
  await storage.sharedCat.removeMember(catId, auth.user_id);
  return json({ success: true, message: '已退出共享分类' });
}

/** DELETE /api/todo/shared-cats/:id/members/:userId  踢人（owner） */
async function kickCatMember({ request, env, params }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const storage = getStorage(env);
  const catId = parseInt(params.id, 10);
  const targetUid = parseInt(params.userId, 10);
  const owned = await requireCatOwner(storage, auth, catId);
  if (owned.error) return owned.error;
  if (targetUid === auth.user_id) return error('不能移出分类创建者', 400);
  const target = await storage.sharedCat.findMember(catId, targetUid);
  if (!target) return error('成员不存在', 404);
  await storage.sharedCat.removeMember(catId, targetUid);
  return json({ success: true, message: '已移出该成员' });
}

/** DELETE /api/todo/shared-cats/:id  解散分类（owner）：任务摘标签保留，成员/分类清理 */
async function deleteSharedCat({ request, env, params }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const storage = getStorage(env);
  const catId = parseInt(params.id, 10);
  const owned = await requireCatOwner(storage, auth, catId);
  if (owned.error) return owned.error;
  await storage.sharedCat.deleteCatCascade(catId);
  return json({ success: true, message: '共享分类已解散，分类下任务保留为你的个人待办' });
}

export {
  createSharedCat, joinSharedCat, mySharedCats,
  getCatInvite, resetCatInvite, listCatMembers,
  leaveSharedCat, kickCatMember, deleteSharedCat
};
