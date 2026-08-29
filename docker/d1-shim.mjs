/**
 * D1 API shim（Cloudflare D1 API 语义 → better-sqlite3 同步 API）
 *
 * 覆盖项目实际用到的接口（见 src/storage/d1-adapter.js）：
 *   db.prepare(sql).bind(...values).first()             -> row | null
 *   db.prepare(sql).bind(...values).run()               -> { success, meta:{last_row_id,changes,duration} }
 *   db.prepare(sql).bind(...values).all()               -> { success, meta, results:[...] }
 *   db.prepare(sql).first() / .all() / .run()           -> 无 bind 时等价上面
 *   db.batch([stmt1, stmt2, ...])                       -> 数组结果，事务包裹
 *   db.exec(sql)                                        -> 迁移/导入用（多语句）
 *
 * better-sqlite3 是同步 API；这里给每个方法包一层 async 保持接口一致，业务代码里的 `await` 不会退化
 */
import BetterSqlite3 from 'better-sqlite3';

/**
 * 内部：把 lastInsertRowid（可能是 BigInt）转成 Number，D1 里 last_row_id 是 Number
 */
function toNumber(x) {
  return typeof x === 'bigint' ? Number(x) : x;
}

class D1PreparedStatement {
  constructor(db, sql, bound = []) {
    this._db = db;
    this._sql = sql;
    this._bound = bound;
  }

  bind(...values) {
    // D1 允许 undefined 参数（转 null）；better-sqlite3 遇 undefined 报错
    // boolean 也顺手转 0/1，防止未来业务代码里出现直接传 true/false
    const safe = values.map(v => {
      if (v === undefined) return null;
      if (typeof v === 'boolean') return v ? 1 : 0;
      return v;
    });
    return new D1PreparedStatement(this._db, this._sql, safe);
  }

  async first(column) {
    const stmt = this._db.prepare(this._sql);
    const row = stmt.get(...this._bound);
    if (row === undefined) return null;
    if (column) return row[column] ?? null;
    return row;
  }

  async run() {
    const start = Date.now();
    const stmt = this._db.prepare(this._sql);
    const info = stmt.run(...this._bound);
    return {
      success: true,
      meta: {
        last_row_id: toNumber(info.lastInsertRowid),
        changes: info.changes,
        duration: Date.now() - start
      }
    };
  }

  async all() {
    const start = Date.now();
    const stmt = this._db.prepare(this._sql);
    // better-sqlite3 的 stmt 默认返回对象；如果是 non-select（DDL），all() 会抛错，
    // 兜底用 iterate 或直接 run（保持与 D1 行为一致：非 SELECT 也能调 all）
    let results;
    try {
      results = stmt.all(...this._bound);
    } catch {
      stmt.run(...this._bound);
      results = [];
    }
    return {
      success: true,
      meta: { last_row_id: 0, changes: 0, duration: Date.now() - start },
      results
    };
  }

  /**
   * batch 内部用：拿到 better-sqlite3 的 Statement + 参数，避免二次 prepare
   */
  _materialize() {
    return { stmt: this._db.prepare(this._sql), params: this._bound, sql: this._sql };
  }
}

/**
 * D1Database shim
 */
export class D1DatabaseShim {
  /**
   * @param {string} filePath - SQLite 文件路径
   */
  constructor(filePath) {
    this._db = new BetterSqlite3(filePath);
    // WAL 模式：并发读写更稳；同步 NORMAL 提速（本项目非金融强一致场景，可接受）
    this._db.pragma('journal_mode = WAL');
    this._db.pragma('synchronous = NORMAL');
    this._db.pragma('foreign_keys = ON');
  }

  prepare(sql) {
    return new D1PreparedStatement(this._db, sql);
  }

  async batch(statements) {
    // 事务包裹，任一失败整体回滚
    const results = [];
    const tx = this._db.transaction((stmts) => {
      for (const s of stmts) {
        const { stmt, params } = s._materialize();
        // 简单探测：SELECT 类走 all，其他走 run
        const isSelect = /^\s*(select|with|pragma)/i.test(s._sql);
        if (isSelect) {
          // PRAGMA 分两类：函数式(table_info/foreign_key_list)返回结果集走 all；
          // 设置式(defer_foreign_keys=on 等)无结果集，better-sqlite3 要求 run，兜底回退。
          let rows = [];
          try {
            rows = stmt.all(...params);
          } catch {
            stmt.run(...params);
          }
          results.push({ success: true, meta: { last_row_id: 0, changes: 0, duration: 0 }, results: rows });
        } else {
          const info = stmt.run(...params);
          results.push({
            success: true,
            meta: { last_row_id: toNumber(info.lastInsertRowid), changes: info.changes, duration: 0 }
          });
        }
      }
    });
    tx(statements);
    return results;
  }

  /**
   * D1 的 exec 支持多语句（用换行分隔）。我们直接透传 better-sqlite3.exec
   */
  async exec(sql) {
    this._db.exec(sql);
    return { count: 0, duration: 0 };
  }

  close() {
    this._db.close();
  }
}
