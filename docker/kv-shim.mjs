/**
 * KV API shim（Cloudflare Workers KV → SQLite 单表模拟）
 *
 * 覆盖项目实际用到的接口（见 src/storage/kv-store.js）：
 *   kv.put(key, value, { expirationTtl: seconds })   -> 存入并设置过期时间
 *   kv.get(key, options?)                             -> 未过期返回字符串，否则 null（cacheTtl 忽略）
 *   kv.delete(key)                                    -> 删除
 *
 * 实现要点：
 *  - 单表 kv_store(key TEXT PK, value TEXT, expire_at INTEGER NULL)
 *  - expire_at 存 Unix 秒时间戳；NULL 表示永久
 *  - 惰性过期：读时判断 expire_at < now 就返回 null 顺便删掉
 *  - 主动清理：每 5 分钟一次全表 DELETE 过期行，防止累积
 */
import BetterSqlite3 from 'better-sqlite3';

export class KVNamespaceShim {
  /**
   * @param {string} filePath - SQLite 文件路径
   */
  constructor(filePath) {
    this._db = new BetterSqlite3(filePath);
    this._db.pragma('journal_mode = WAL');
    this._db.pragma('synchronous = NORMAL');
    this._db.exec(`
      CREATE TABLE IF NOT EXISTS kv_store (
        key       TEXT PRIMARY KEY,
        value     TEXT NOT NULL,
        expire_at INTEGER
      );
      CREATE INDEX IF NOT EXISTS idx_kv_expire ON kv_store(expire_at);
    `);

    // 预编译语句（better-sqlite3 反复用同一 stmt 更快）
    this._stmt = {
      getRow: this._db.prepare('SELECT value, expire_at FROM kv_store WHERE key = ?'),
      putNoTtl: this._db.prepare('INSERT INTO kv_store (key, value, expire_at) VALUES (?, ?, NULL) ON CONFLICT(key) DO UPDATE SET value=excluded.value, expire_at=NULL'),
      putTtl: this._db.prepare('INSERT INTO kv_store (key, value, expire_at) VALUES (?, ?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value, expire_at=excluded.expire_at'),
      del: this._db.prepare('DELETE FROM kv_store WHERE key = ?'),
      purgeExpired: this._db.prepare('DELETE FROM kv_store WHERE expire_at IS NOT NULL AND expire_at < ?')
    };

    // 每 5 分钟清一次过期键
    this._cleanupTimer = setInterval(() => {
      try { this._stmt.purgeExpired.run(Math.floor(Date.now() / 1000)); } catch {}
    }, 5 * 60 * 1000);
    this._cleanupTimer.unref?.();
  }

  async get(key, _options) {
    // options 里的 cacheTtl 我们不做实现（本项目仅用它做边缘缓存优化，无功能影响）
    const row = this._stmt.getRow.get(key);
    if (!row) return null;
    if (row.expire_at != null && row.expire_at < Math.floor(Date.now() / 1000)) {
      // 惰性过期：读到已过期立即删掉
      this._stmt.del.run(key);
      return null;
    }
    return row.value;
  }

  async put(key, value, options = {}) {
    const ttl = options && options.expirationTtl;
    if (ttl && ttl > 0) {
      const expireAt = Math.floor(Date.now() / 1000) + ttl;
      this._stmt.putTtl.run(key, value, expireAt);
    } else {
      this._stmt.putNoTtl.run(key, value);
    }
  }

  async delete(key) {
    this._stmt.del.run(key);
  }

  close() {
    clearInterval(this._cleanupTimer);
    this._db.close();
  }
}
