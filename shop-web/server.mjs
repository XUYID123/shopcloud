import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const staticFiles = new Map([
  ['/', ['index.html', 'text/html; charset=utf-8']],
  ['/crm-catalog', ['crm-catalog.html', 'text/html; charset=utf-8']],
  ['/crm-catalog.js', ['crm-catalog.js', 'text/javascript; charset=utf-8']],
  ['/app.js', ['app.js', 'text/javascript; charset=utf-8']],
  ['/styles.css', ['styles.css', 'text/css; charset=utf-8']],
  ['/flour-hero.png', ['flour-hero.png', 'image/png']],
]);
const catalogPath = /^\/api\/v1\/(?:categories|products(?:\/[1-9]\d*)?|skus\/[1-9]\d*)$/;
const crmPaths = new Set(['/api/v1/products/crm-catalog', '/api/v1/products/crm-catalog/overview']);
const localHost = /^(?:127\.0\.0\.1|localhost)(?::[0-9]{1,5})?$/;
const authPaths = new Set(['/api/auth/login', '/api/auth/logout']);

function json(response, status, code, message) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify({ code, message, data: null }));
}

export function createStorefrontServer(gateway = process.env.SHOP_WEB_GATEWAY_URL ?? 'http://127.0.0.1:8888',
  requestGateway = fetch, crmToken = process.env.SHOP_PRODUCT_CRM_PREVIEW_TOKEN ?? '') {
  const { origin, host } = new URL(gateway);
  if (!/^https?:\/\//.test(origin)) throw new Error('SHOP_WEB_GATEWAY_URL must be an HTTP(S) origin');
  const crmEnabled = /^[a-f0-9]{64}$/.test(crmToken) && localHost.test(host);

  return createServer(async (request, response) => {
    const url = new URL(request.url, 'http://localhost');
    if (url.pathname.startsWith('/api/')) {
      const catalog = request.method === 'GET' && catalogPath.test(url.pathname);
      const crm = request.method === 'GET' && crmPaths.has(url.pathname) && crmEnabled
        && localHost.test(request.headers.host ?? '');
      const auth = request.method === 'POST' && authPaths.has(url.pathname);
      if (!catalog && !crm && !auth) return json(response, 404, 'NOT_FOUND', '该接口未在商城前端启用');
      if (auth && request.headers.origin && request.headers.origin !== `http://${request.headers.host}`) {
        return json(response, 403, 'FORBIDDEN', '请从当前商城页面发起请求');
      }

      try {
        let body;
        if (auth) {
          const chunks = [];
          let length = 0;
          for await (const chunk of request) {
            length += chunk.length;
            if (length <= 8192) chunks.push(chunk);
          }
          if (length > 8192) return json(response, 413, 'VALIDATION_ERROR', '请求内容过长');
          body = Buffer.concat(chunks);
        }
        const headers = { Accept: 'application/json' };
        if (auth) headers['Content-Type'] = 'application/json';
        if (crm) headers['X-Crm-Preview-Token'] = crmToken;
        const upstream = await requestGateway(new URL(url.pathname + url.search, origin), {
          method: request.method, headers, body, redirect: 'error', signal: AbortSignal.timeout(6000),
        });
        const payload = Buffer.from(await upstream.arrayBuffer());
        const outgoing = { 'Content-Type': upstream.headers.get('content-type') ?? 'application/json', 'Cache-Control': 'no-store' };
        for (const name of ['x-trace-id', 'x-request-id', 'retry-after']) {
          if (upstream.headers.has(name)) outgoing[name] = upstream.headers.get(name);
        }
        response.writeHead(upstream.status, outgoing);
        return response.end(payload);
      } catch {
        return json(response, 503, 'DEPENDENCY_UNAVAILABLE', '商城网关暂不可用，请稍后重试');
      }
    }

    const asset = staticFiles.get(url.pathname);
    if (!asset || !['GET', 'HEAD'].includes(request.method)) return json(response, 404, 'NOT_FOUND', '页面不存在');
    try {
      const bytes = await readFile(new URL(`./public/${asset[0]}`, import.meta.url));
      response.writeHead(200, { 'Content-Type': asset[1], 'Cache-Control': 'no-cache',
        'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer' });
      response.end(request.method === 'HEAD' ? undefined : bytes);
    } catch {
      json(response, 404, 'NOT_FOUND', '资源不存在');
    }
  });
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const port = Number(process.env.SHOP_WEB_PORT ?? 5173);
  createStorefrontServer().listen(port, '127.0.0.1', () => console.log(`商城前端 http://127.0.0.1:${port}`));
}
