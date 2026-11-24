import { Readable } from 'node:stream';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createStorefrontServer } from './server.mjs';

/** 测试实际 HTTP 处理函数，使用内存请求替身，不开启监听端口。 */
function request(server, path, method = 'GET', body = '', headers = { host: '127.0.0.1:5173' }) {
  return new Promise((resolve) => {
    const incoming = Readable.from(body ? [Buffer.from(body)] : []);
    Object.assign(incoming, { method, url: path, headers });
    const response = {
      writeHead(status, sentHeaders) { this.status = status; this.headers = sentHeaders; },
      end(payload) { resolve({ status: this.status, headers: this.headers, payload }); },
    };
    server.emit('request', incoming, response);
  });
}

test('serves the storefront without exposing the pending catalog review file', async () => {
  const server = createStorefrontServer();
  const home = await request(server, '/');
  assert.equal(home.status, 200);
  assert.match(home.payload.toString(), /面粉选购/);
  assert.equal((await request(server, '/flour-hero.png')).headers['Content-Type'], 'image/png');
  assert.equal((await request(server, '/catalog-review.json')).status, 404);
});

test('proxies the catalog filter and keeps the upstream Trace ID', async () => {
  const server = createStorefrontServer('http://127.0.0.1:8888', async (url, options) => {
    assert.equal(url.href, 'http://127.0.0.1:8888/api/v1/products?page=2&sort=PRICE_ASC');
    assert.equal(options.method, 'GET');
    return new Response('{"code":"OK","data":{"items":[]}}', { headers: { 'x-trace-id': '9f40d39b780aea52' } });
  });
  const result = await request(server, '/api/v1/products?page=2&sort=PRICE_ASC');
  assert.equal(result.status, 200);
  assert.equal(result.headers['x-trace-id'], '9f40d39b780aea52');
  assert.deepEqual(JSON.parse(result.payload.toString()).data.items, []);
});

test('forwards login to the configured gateway and blocks unimplemented payment or admin routes', async () => {
  let calls = 0;
  const server = createStorefrontServer('http://127.0.0.1:8888', async (url, options) => {
    calls += 1;
    assert.equal(url.pathname, '/api/auth/login');
    assert.equal(options.body.toString(), '{"username":"test-user","password":"fixture"}');
    return new Response('{"code":"INVALID_CREDENTIALS"}', { status: 401 });
  });
  const body = '{"username":"test-user","password":"fixture"}';
  assert.equal((await request(server, '/api/auth/login', 'POST', body)).status, 401);
  assert.equal((await request(server, '/api/v1/payment-sessions', 'POST', '{}')).status, 404);
  assert.equal((await request(server, '/api/v1/admin/products', 'POST', '{}')).status, 404);
  assert.equal((await request(server, '/api/auth/login', 'POST', body, { host: '127.0.0.1:5173', origin: 'https://other.example' })).status, 403);
  assert.equal(calls, 1);
});

test('reports a gateway outage separately from an empty catalog', async () => {
  const server = createStorefrontServer('http://127.0.0.1:8888', async () => { throw new Error('Fixture unavailable'); });
  const result = await request(server, '/api/v1/products');
  assert.equal(result.status, 503);
  assert.equal(JSON.parse(result.payload).code, 'DEPENDENCY_UNAVAILABLE');
});

test('keeps CRM preview disabled by default and only forwards its token to the local CRM endpoint', async () => {
  const path = '/api/v1/products/crm-catalog';
  const overview = path + '/overview';
  const token = 'a'.repeat(64);
  const calls = [];
  const upstream = async (url, options) => {
    calls.push([url.pathname, url.search, options.headers['X-Crm-Preview-Token']]);
    return new Response('{"code":"OK"}');
  };
  const gateway = 'http://127.0.0.1:8888';
  const disabled = createStorefrontServer(gateway, upstream, '');
  const enabled = createStorefrontServer(gateway, upstream, token);
  assert.equal((await request(disabled, path)).status, 404);
  assert.equal((await request(enabled, path, 'GET', '', { host: 'example.test' })).status, 404);
  assert.equal((await request(createStorefrontServer('https://example.test', upstream, token), path)).status, 404);
  assert.equal((await request(enabled, path, 'POST')).status, 404);
  assert.equal((await request(disabled, overview)).status, 404);
  assert.equal((await request(enabled, path + '?page=2&size=12')).status, 200);
  assert.equal((await request(enabled, overview + '?keyword=abc')).status, 200);
  assert.equal((await request(enabled, path + '/unknown')).status, 404);
  assert.equal((await request(enabled, '/api/v1/products')).status, 200);
  assert.deepEqual(calls, [[path, '?page=2&size=12', token], [overview, '?keyword=abc', token],
    ['/api/v1/products', '', undefined]]);
});
