/**
 * 待办共享性能诊断脚本（只读，不修改数据）
 * 用法（在 docker-compose.yml 所在目录、容器已停机或运行中均可）：
 *   node docker/todo-perf-check.mjs
 * 输出：真实库的任务量、共享索引是否存在、列表查询计划与实测耗时、响应体大小
 */
import Database from 'better-sqlite3';
import { readFileSync, existsSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DB_PATH = join(ROOT, 'docker-data', 'd1.sqlite');

if (!existsSync(DB_PATH)) {
  console.error('找不到数据库文件:', DB_PATH);
  console.error('请确认 docker-compose.yml 与 docker-data/ 在当前项目目录下。');
  process.exit(1);
}

const db = new Database(DB_PATH, { readonly: true });

const line = (s) => console.log(s);
line('================ 待办共享性能诊断 ================');

// 1. 数据量
const total = db.prepare('SELECT COUNT(*) c FROM todos').get().c;
const shared = db.prepare('SELECT COUNT(*) c FROM todos WHERE shared_cat_id IS NOT NULL').get().c;
const users = db.prepare('SELECT COUNT(DISTINCT user_id) c FROM todos').get().c;
line(`[数据量] todos 总行数=${total}, 其中共享任务=${shared}, 涉及用户=${users}`);

// 2. todos 列（确认 shared_cat_id 已建）
const cols = db.prepare('PRAGMA table_info(todos)').all().map(c => c.name);
line(`[列] shared_cat_id 存在: ${cols.includes('shared_cat_id') ? '是 ✅' : '否 ❌（迁移未应用！）'}`);
line(`[列] list_id 残留(旧模型,应无): ${cols.includes('list_id') ? '是 ⚠️(无害但建议清理)' : '否 ✅'}`);

// 3. todos 索引（确认共享索引存在）
const idx = db.prepare("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='todos'").all().map(r => r.name);
line(`[索引] todos 索引: ${idx.join(', ')}`);
line(`[索引] idx_todos_shared_cat 存在: ${idx.includes('idx_todos_shared_cat') ? '是 ✅' : '否 ❌（会导致全表扫描！）'}`);

// 4. 共享分类与成员
try {
  const cats = db.prepare('SELECT COUNT(*) c FROM todo_shared_cats').get().c;
  const mems = db.prepare('SELECT COUNT(*) c FROM todo_shared_cat_members').get().c;
  line(`[共享] 分类数=${cats}, 成员关系数=${mems}`);
} catch (e) {
  line(`[共享] 共享表不存在 ❌: ${e.message}`);
}

// 5. 迁移记录
line('[迁移] 0015 相关记录:');
db.prepare("SELECT name FROM _migrations WHERE name LIKE '%0015%'").all().forEach(r => line('   - ' + r.name));

// 6. 实测列表查询（对每个有任务的用户）
const VIS = `SELECT t.* FROM todos t
  WHERE (t.user_id = ? AND t.shared_cat_id IS NULL)
     OR t.shared_cat_id IN (SELECT m.cat_id FROM todo_shared_cat_members m WHERE m.user_id = ?)
  ORDER BY t.sort_order, t.id`;
line('---------------- 逐用户列表查询 ----------------');
const uids = db.prepare('SELECT DISTINCT user_id FROM todos').all().map(r => r.user_id).slice(0, 10);
for (const uid of uids) {
  const plan = db.prepare('EXPLAIN QUERY PLAN ' + VIS).all(uid, uid).map(r => r.detail).join(' | ');
  const t0 = process.hrtime.bigint();
  const rows = db.prepare(VIS).all(uid, uid);
  const ms = Number(process.hrtime.bigint() - t0) / 1e6;
  const kb = (JSON.stringify(rows).length / 1024).toFixed(1);
  const scan = plan.includes('SCAN t') ? ' ⚠️全表扫描' : ' ✅走索引';
  line(`用户 ${uid}: ${rows.length} 行, ${ms.toFixed(2)}ms, 响应≈${kb}KB${scan}`);
  if (plan.includes('SCAN t')) line(`   计划: ${plan}`);
}

line('================================================');
line('判读:');
line('- 若有 ⚠️/❌: 把本输出完整发给开发者, 索引缺失或迁移未应用是卡顿主因。');
line('- 若全部 ✅ 但单用户行数上千: 瓶颈是大数据量全量传输+前端渲染, 属架构优化范畴。');
db.close();
