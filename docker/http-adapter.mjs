/**
 * Node HTTP Server ↔ Fetch API 适配层
 *
 * 把 Node 的 http.createServer(req, res) 转成 Workers 的 fetch(request, env, ctx) 调用
 *
 * 项目只用到了：
 *   - request.json()        -> 全部 API handler 均如此
 *   - request.headers.get() -> Cookie / Content-Type 等
 *   - request.url           -> 路由解析
 *   - request.method        -> GET/POST/PUT/DELETE
 *
 * 响应侧：
 *   - status / headers / body（含 Set-Cookie 多值）
 *   - 301/302 Location
 */
import { createServer } from 'node:http';
import { URL } from 'node:url';
import { gzipSync } from 'node:zlib';

/** 读 Node IncomingMessage 完整 body 为 Buffer */
function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

/**
 * Node IncomingMessage → Fetch API Request
 * GET/HEAD 不读 body
 */
async function nodeReqToFetchRequest(req) {
  const host = req.headers['host'] || 'localhost';
  const url = new URL(req.url || '/', `http://${host}`);
  const method = req.method || 'GET';

  let body = null;
  if (method !== 'GET' && method !== 'HEAD') {
    const buf = await readBody(req);
    body = buf.length > 0 ? buf : null;
  }

  return new Request(url, {
    method,
    headers: req.headers,
    body,
    duplex: 'half'
  });
}

/**
 * 把 Fetch Headers 落到 Node ServerResponse
 * 特殊处理 set-cookie：Fetch Headers 里可能有多个，需以数组形式写出
 */
function writeHeaders(res, headers, status) {
  const outHeaders = {};
  // getSetCookie 是 Node 20+ / undici 提供的方法
  let setCookies = null;
  if (typeof headers.getSetCookie === 'function') {
    setCookies = headers.getSetCookie();
  }
  for (const [k, v] of headers.entries()) {
    if (k.toLowerCase() === 'set-cookie') continue; // 单独处理
    outHeaders[k] = v;
  }
  if (setCookies && setCookies.length > 0) {
    outHeaders['set-cookie'] = setCookies;
  }
  res.writeHead(status, outHeaders);
}

/**
 * 创建 HTTP 服务器，桥接到 Worker 风格 fetch 入口
 * @param {function(Request, Object, Object): Promise<Response>} fetchHandler
 * @param {Object} env
 * @param {function} createCtx - 每次请求创建 ctx 的工厂
 */
export function createHttpServer(fetchHandler, env, createCtx) {
  return createServer(async (req, res) => {
    try {
      const request = await nodeReqToFetchRequest(req);
      const ctx = createCtx();
      const response = await fetchHandler(request, env, ctx);

      // 一次性读出 body（项目响应体都不大：HTML 页面 + JSON）
      let buf = Buffer.from(await response.arrayBuffer());

      // gzip 压缩：文本类响应（html/js/css/json/svg）且客户端支持时压缩。
      // 仅作用于 Node/Docker 宿主（Workers 线上由 Cloudflare 边缘自动 br/gzip，不经过此处）。
      const acceptEnc = String(req.headers['accept-encoding'] || '');
      const ct = String(response.headers.get('content-type') || '');
      if (acceptEnc.includes('gzip') && buf.length > 1024 && /text|javascript|json|css|html|svg|xml/.test(ct)) {
        buf = gzipSync(buf);
        response.headers.set('Content-Encoding', 'gzip');
        response.headers.delete('Content-Length'); // 交由 res.end 按压缩后字节重设
        response.headers.set('Vary', 'Accept-Encoding');
      }

      writeHeaders(res, response.headers, response.status);
      res.end(buf);
    } catch (err) {
      console.error('[http] handler error:', err);
      if (!res.headersSent) {
        res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
      }
      res.end(JSON.stringify({ success: false, message: '服务器错误', error: err.message }));
    }
  });
}
