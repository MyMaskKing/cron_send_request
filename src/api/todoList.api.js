/**
 * 待办共享目录 API
 *
 * 目录 = 一个自引用 list_id 的顶层任务（根）。owner 生成邀请码，家人凭码加入后
 * 目录内任务对所有成员可见可写（数据不搬家，user_id 恒为 owner；真实操作人记
 * created_by/done_by）。与模块级共享(share.api.js, X-Data-As)是两套独立机制。
 */

import { json, error } from '../router.js';
import { getStorage } from '../storage/adapter.js';
import { requireAuth } from '../auth/middleware.js';
import { genInviteCode } from './share.api.js';
import { resolveBaseUrl } from '../config.js';

/** 生成不与现存目录邀请冲突的 8 位码（极小概率冲突兜底加长再试一次） */
async function uniqueListCode(storage) {
  for (let i = 0; i < 5; i++) {
    const code = genInviteCode();
    const exists = await storage.todoList.findInviteByCode(code);
    if (!exists) return code;
  }
  return genInviteCode(12);
}

/** 校验目录存在且当前用户是 owner；返回 { root } 或 { error: Response } */
async function requireListOwner(storage, auth, listId) {
  const root = await storage.todo.findById(listId);
  if (!root || root.list_id !== root.id || root.parent_id != null) {
    return { error: error('共享目录不存在', 404) };
  }
  const m = await storage.todoList.findMember(listId, auth.user_id);
  if (!m || m.role !== 'owner') return { error: error('仅目录创建者可操作', 403) };
  return { root };
}

/** POST /api/todo/lists  新建共享目录  body: { title } */
async function createSharedList({ request, env, url }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const body = await request.json().catch(() => ({}));
  const title = (body.title || '').trim();
  if (!title) return error('请填写目录名称');

  const storage = getStorage(env);
  // 根任务: child_due=1 目录壳(成员往里加各自带日期的任务), 归属本人
  const rootId = await storage.todo.create(auth.user_id, {
    parent_id: null, title, child_due: 1, list_id: null, created_by: auth.user_id
  });
  // 根自引用回填 list_id
  await storage.todo.attachList(rootId, [rootId]);
  const code = await uniqueListCode(storage);
  await storage.todoList.createList(rootId, auth.user_id, code);
  const base = await resolveBaseUrl(storage, env, url);
  return json({ success: true, id: rootId, code, link: `${base}/todo?join=${code}`, message: '共享目录已创建' });
}

/** POST /api/todo/lists/:id/convert  现有个人顶层清单转为共享目录 */
async function convertList({ request, env, params, url }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const storage = getStorage(env);
  const id = parseInt(params.id, 10);
  const root = await storage.todo.findById(id);
  // 转换是 owner 自身行为: 必须本人拥有(不走 X-Data-As)的个人顶层清单
  if (!root || root.user_id !== auth.user_id) return error('清单不存在', 404);
  if (root.parent_id != null) return error('仅顶层清单可转为共享目录', 400);
  if (root.list_id != null) return error('该清单已是共享目录', 400);

  const descendants = await storage.todo.collectDescendantIds(id);
  await storage.todo.attachList(id, [id, ...descendants]);
  const code = await uniqueListCode(storage);
  await storage.todoList.createList(id, auth.user_id, code);
  const base = await resolveBaseUrl(storage, env, url);
  return json({ success: true, id, code, link: `${base}/todo?join=${code}`, message: '已转为共享目录，可邀请家人加入' });
}

/** GET /api/todo/lists/:id/invite  获取邀请码与链接（owner） */
async function getListInvite({ request, env, params, url }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const storage = getStorage(env);
  const listId = parseInt(params.id, 10);
  const owned = await requireListOwner(storage, auth, listId);
  if (owned.error) return owned.error;
  const inv = await storage.todoList.findInviteByList(listId);
  if (!inv) return error('邀请不存在', 404);
  const base = await resolveBaseUrl(storage, env, url);
  return json({ success: true, code: inv.code, link: `${base}/todo?join=${inv.code}` });
}

/** POST /api/todo/lists/:id/invite/reset  重置邀请码（旧码失效，成员保留） */
async function resetListInvite({ request, env, params }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const storage = getStorage(env);
  const listId = parseInt(params.id, 10);
  const owned = await requireListOwner(storage, auth, listId);
  if (owned.error) return owned.error;
  const code = await uniqueListCode(storage);
  await storage.todoList.updateInviteCode(listId, code);
  return json({ success: true, code, message: '邀请码已重置，旧码立即失效' });
}

/** POST /api/todo/lists/join  凭码加入  body: { code } */
async function joinList({ request, env }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const body = await request.json().catch(() => ({}));
  const code = (body.code || '').trim();
  if (!code) return error('请输入邀请码');
  const storage = getStorage(env);
  const inv = await storage.todoList.findInviteByCode(code);
  if (!inv) return error('邀请码无效或已失效');
  const root = await storage.todo.findById(inv.list_id);
  if (!root || root.list_id !== root.id) return error('邀请码无效或已失效');
  if (root.user_id === auth.user_id) return error('这是你自己创建的目录，无需加入');
  await storage.todoList.addMember(inv.list_id, auth.user_id);
  return json({ success: true, list_id: inv.list_id, title: root.title, message: `已加入「${root.title}」` });
}

/** GET /api/todo/lists/mine  我参与的目录（owner 附带邀请码） */
async function myLists({ request, env }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const storage = getStorage(env);
  const rows = await storage.todoList.listMyLists(auth.user_id);
  return json({
    success: true,
    lists: rows.map(l => ({
      list_id: l.list_id,
      title: l.list_title,
      role: l.role,
      owner_name: l.owner_nickname || l.owner_username,
      member_count: l.member_count,
      joined_at: l.joined_at,
      code: l.role === 'owner' ? l.invite_code : null
    }))
  });
}

/** GET /api/todo/lists/:id/members  成员列表（目录成员） */
async function listMembers({ request, env, params }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const storage = getStorage(env);
  const listId = parseInt(params.id, 10);
  const me = await storage.todoList.findMember(listId, auth.user_id);
  if (!me) return error('你不是该目录成员', 403);
  const root = await storage.todo.findById(listId);
  if (!root || root.list_id !== root.id) return error('共享目录不存在', 404);
  const members = await storage.todoList.listMembers(listId);
  return json({
    success: true,
    title: root.title,
    members: members.map(m => ({
      user_id: m.user_id,
      nickname: m.nickname || m.username,
      role: m.role,
      joined_at: m.joined_at
    }))
  });
}

/** POST /api/todo/lists/:id/leave  退出目录（editor；owner 只能解散） */
async function leaveList({ request, env, params }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const storage = getStorage(env);
  const listId = parseInt(params.id, 10);
  const me = await storage.todoList.findMember(listId, auth.user_id);
  if (!me) return error('你不是该目录成员', 403);
  if (me.role === 'owner') return error('创建者不能退出，请直接解散目录', 400);
  await storage.todoList.removeMember(listId, auth.user_id);
  return json({ success: true, message: '已退出共享目录' });
}

/** DELETE /api/todo/lists/:id/members/:userId  踢人（owner） */
async function kickMember({ request, env, params }) {
  const auth = await requireAuth(request, env);
  if (auth instanceof Response) return auth;
  const storage = getStorage(env);
  const listId = parseInt(params.id, 10);
  const targetUid = parseInt(params.userId, 10);
  const owned = await requireListOwner(storage, auth, listId);
  if (owned.error) return owned.error;
  if (targetUid === auth.user_id) return error('不能移出目录创建者', 400);
  const target = await storage.todoList.findMember(listId, targetUid);
  if (!target) return error('成员不存在', 404);
  await storage.todoList.removeMember(listId, targetUid);
  return json({ success: true, message: '已移出该成员' });
}

export {
  createSharedList, convertList,
  getListInvite, resetListInvite,
  joinList, myLists, listMembers, leaveList, kickMember
};
