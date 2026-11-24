import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

const base = (__ENV.BASE_URL || '').replace(/\/$/, '');
const mode = __ENV.MODE || 'catalog';
if (!/^https?:\/\/[^/]+$/.test(base) || !['catalog', 'order', 'payment', 'stock-contention'].includes(mode)) {
  throw new Error('Supply a BASE_URL origin and choose catalog, order, payment or stock-contention');
}
const trade = mode !== 'catalog';
const contention = mode === 'stock-contention';
const payment = mode === 'payment';
const fixtures = trade ? new SharedArray('checkout inputs', () => JSON.parse(open(__ENV.FIXTURES_FILE))) : null;
const ids = (__ENV.PRODUCT_IDS || '').split(',').filter(Boolean);
if (!trade && (ids.length === 0 || ids.some(id => !/^[1-9][0-9]{0,18}$/.test(id)))) {
  throw new Error('Supply comma-separated PRODUCT_IDS for the catalog scenario');
}
const stock = new Trend('order_stock_ms', true);
const acceptedDuration = new Trend('order_accept_ms', true);
const paymentConfirmation = new Trend('payment_confirmation_ms', true);
const reserved = new Counter('orders_reserved');
const rejected = new Counter('orders_rejected');
const throttled = new Counter('orders_throttled');
const unexpected = new Rate('unexpected_outcome');
const rate = Number(__ENV.RATE || (trade ? 200 : 2000));
const duration = __ENV.DURATION || '15m';
const preAllocatedVUs = Number(__ENV.VUS || (trade ? 500 : 400));
const attemptLimit = contention ? fixtures.length : Infinity;
const scenarios = contention
  ? { measured: { executor: 'shared-iterations', vus: preAllocatedVUs, iterations: attemptLimit, maxDuration: duration } }
  : { measured: { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration, preAllocatedVUs } };
const thresholds = { unexpected_outcome: [contention ? 'rate==0' : 'rate<0.001'] };
if (!contention) thresholds.dropped_iterations = ['count==0'];
if (!trade) thresholds.http_req_duration = ['p(95)<200', 'p(99)<500'];
if (trade && !contention) {
  thresholds.order_accept_ms = ['p(95)<500', 'p(99)<1000'];
  thresholds.order_stock_ms = ['p(95)<2000', 'p(99)<5000'];
}
if (payment) thresholds.payment_confirmation_ms = ['p(95)<3000'];
if (contention) {
  const expected = Number(__ENV.EXPECT_RESERVED);
  if (!Number.isInteger(expected) || expected < 1 || expected > attemptLimit) {
    throw new Error('stock-contention needs EXPECT_RESERVED, the expected successful reservations');
  }
  thresholds.orders_reserved = [`count==${expected}`];
  thresholds.orders_rejected = [`count==${attemptLimit - expected}`];
  thresholds.iterations = [`count==${attemptLimit}`];
}
export const options = { scenarios, thresholds, summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max'] };

function payload(response) {
  try {
    return response.json('data');
  } catch {
    return null;
  }
}

function confirmPayment(orderNo, headers, adminAccessToken) {
  if (!adminAccessToken) return false;
  const session = http.post(base + '/api/v1/payment-sessions', JSON.stringify({ orderNo, provider: 'MOCK' }), {
    headers: { ...headers, 'Idempotency-Key': orderNo }, tags: { name: 'payment.session' }, timeout: '5s',
  });
  const paymentNo = session.status === 201 ? payload(session)?.paymentNo : null;
  if (!paymentNo) return false;
  const start = Date.now();
  const event = http.post(
    `${base}/api/v1/test/payment-sessions/${encodeURIComponent(paymentNo)}/payment-events`,
    JSON.stringify({ type: 'SUCCEEDED' }), {
      headers: { Authorization: `Bearer ${adminAccessToken}`, 'Content-Type': 'application/json',
        'Idempotency-Key': paymentNo },
      tags: { name: 'payment.mock' }, timeout: '5s',
    });
  if (event.status !== 201) return false;
  for (let attempt = 0; attempt < 40; attempt++) {
    const read = http.get(`${base}/api/v1/orders/${encodeURIComponent(orderNo)}`, {
      headers, tags: { name: 'payment.order' }, timeout: '3s',
    });
    const order = read.status === 200 ? payload(read) : null;
    if (order?.orderStatus === 'PAID' && order.inventoryStatus === 'CONFIRMED') {
      paymentConfirmation.add(Date.now() - start);
      return true;
    }
    if (!order || !['UNPAID', 'PAID'].includes(order.orderStatus)
        || ['EXCEPTION', 'REFUNDING', 'REFUNDED'].includes(order.paymentStatus)) return false;
    sleep(0.25);
  }
  return false;
}

export default function () {
  const index = exec.scenario.iterationInTest;
  if (!trade) {
    const list = index % 5 === 4;
    const path = list ? '/api/v1/products?page=1&size=20' : `/api/v1/products/${encodeURIComponent(ids[index % ids.length])}`;
    const result = http.get(base + path, { tags: { name: list ? 'catalog.list' : 'catalog.detail' } });
    unexpected.add(result.status !== 200);
    return;
  }

  const fixture = fixtures[index];
  if (!fixture) {
    exec.test.abort('The run exhausted its checkout inputs');
    return;
  }
  if (contention) {
    reserved.add(0);
    rejected.add(0);
  }
  const headers = { Authorization: `Bearer ${fixture.accessToken}`, 'Content-Type': 'application/json' };
  const quote = http.post(base + '/api/v1/checkout/quotes',
    JSON.stringify({ addressId: fixture.addressId, items: fixture.items }),
    { headers, tags: { name: 'checkout.quote' }, timeout: '5s' });
  const quoteId = quote.status === 201 ? payload(quote)?.quoteId : null;
  if (!quoteId) {
    if (quote.status === 429) throttled.add(1);
    unexpected.add(true);
    return;
  }
  const start = Date.now();
  const response = http.post(base + '/api/v1/orders', JSON.stringify({ quoteId }), {
    headers: { ...headers, 'Idempotency-Key': quoteId }, tags: { name: 'order.create' },
    timeout: '5s',
  });
  if (response.status !== 202) {
    if (response.status === 429) throttled.add(1);
    unexpected.add(true);
    return;
  }
  acceptedDuration.add(response.timings.duration);
  const orderNo = payload(response)?.orderNo;
  if (!orderNo) {
    unexpected.add(true);
    return;
  }
  for (let attempt = 0; attempt < 40; attempt++) {
    const read = http.get(`${base}/api/v1/orders/${encodeURIComponent(orderNo)}`, {
      headers, tags: { name: 'order.detail' }, timeout: '3s',
    });
    const order = read.status === 200 ? payload(read) : null;
    const status = order?.orderStatus;
    if (status === 'UNPAID') {
      stock.add(Date.now() - start);
      reserved.add(1);
      unexpected.add(payment && !confirmPayment(orderNo, headers, fixture.adminAccessToken));
      return;
    }
    if (status === 'FAILED' && contention && order.failureCode === 'STOCK_NOT_ENOUGH') {
      rejected.add(1);
      unexpected.add(false);
      return;
    }
    if (status !== 'PREPARING') break;
    sleep(0.25);
  }
  unexpected.add(true);
}
