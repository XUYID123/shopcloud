const $ = (id) => document.getElementById(id);
const currency = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' });
const CART_KEY = 'flour-shop-cart-v1';
const validId = (value) => typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value);
const money = (fen) => Number.isSafeInteger(fen) && fen >= 0 ? currency.format(fen / 100) : '价格待确认';

function element(name, text, className) {
  const node = document.createElement(name);
  if (text != null) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function button(text, callback, className = 'plain') {
  const node = element('button', text, className);
  node.type = 'button';
  node.addEventListener('click', callback);
  return node;
}

function readCart() {
  try {
    const saved = JSON.parse(localStorage.getItem(CART_KEY) ?? '[]');
    return Array.isArray(saved) ? saved.filter((item) => item && validId(item.skuId)
      && typeof item.name === 'string' && typeof item.spec === 'string'
      && Number.isSafeInteger(item.priceFen) && item.priceFen >= 0 && item.priceFen <= 10_000_000
      && Number.isInteger(item.quantity) && item.quantity >= 1 && item.quantity <= 100).slice(0, 20) : [];
  } catch {
    return [];
  }
}

const state = { cart: readCart(), account: null, page: 1, request: 0, panel: 0 };

async function api(path, options) {
  const response = await fetch(path, options);
  const trace = response.headers.get('x-trace-id');
  const payload = await response.json().catch(() => ({}));
  if (!response.ok || payload.code !== 'OK') {
    throw new Error(`${payload.message ?? '商城接口暂不可用'}${trace ? `（${trace}）` : ''}`);
  }
  return payload.data;
}

function updateCartCount() {
  $('bag-count').textContent = String(state.cart.reduce((total, item) => total + item.quantity, 0));
}

function saveCart() {
  try { localStorage.setItem(CART_KEY, JSON.stringify(state.cart)); } catch { /* 购物袋仍可在当前页面使用。 */ }
  updateCartCount();
}

function openPanel(title) {
  state.panel += 1;
  const dialog = $('panel');
  $('panel-title').textContent = title;
  $('panel-body').replaceChildren();
  if (!dialog.open) dialog.showModal();
  return $('panel-body');
}

function errorNode(error) { return element('p', error.message, 'message error'); }

function secureImage(value) {
  try {
    const url = new URL(value, location.origin);
    return url.protocol === 'https:' || url.origin === location.origin ? url.href : null;
  } catch { return null; }
}

function card(product) {
  const article = element('article', null, 'product');
  const picture = element('div', null, 'product-picture');
  const src = product.imageUrl && secureImage(product.imageUrl);
  if (src) {
    const image = element('img');
    image.src = src;
    image.alt = product.name;
    image.loading = 'lazy';
    image.referrerPolicy = 'no-referrer';
    image.addEventListener('error', () => image.remove());
    picture.append(image);
  }
  const copy = element('div', null, 'product-copy');
  copy.append(element('h3', product.name), element('p', `${money(product.minPriceFen)} 起`, 'price'),
    button('选规格', () => showProduct(product), 'button primary'));
  article.append(picture, copy);
  return article;
}

async function loadCategories() {
  try {
    const categories = await api('/api/v1/categories');
    for (const category of categories) {
      if (!validId(category.categoryId)) continue;
      const option = element('option', category.name);
      option.value = category.categoryId;
      $('category').append(option);
    }
  } catch {
    // 分类暂不可用时仍支持全量列表与关键词搜索。
  }
}

async function loadCatalog(page = 1) {
  const id = ++state.request;
  const query = new URLSearchParams(new FormData($('filters')));
  for (const key of ['keyword', 'categoryId']) {
    const value = query.get(key)?.trim();
    if (value) query.set(key, value); else query.delete(key);
  }
  query.set('page', String(page));
  query.set('size', '8');
  const status = $('catalog-message');
  status.textContent = '正在读取商品…';
  status.hidden = false;
  status.classList.remove('error');
  $('products').replaceChildren();
  $('catalog-count').textContent = '';
  $('pagination').hidden = true;
  try {
    const data = await api(`/api/v1/products?${query}`);
    if (id !== state.request) return;
    const products = data.items.filter((item) => validId(item.productId));
    state.page = data.page;
    $('catalog-count').textContent = `共 ${data.total} 件商品`;
    $('products').replaceChildren(...products.map(card));
    status.hidden = products.length > 0;
    status.textContent = query.has('keyword') || query.has('categoryId') ? '没有找到符合条件的商品。' : '暂无已上架商品。';
    const pages = Math.ceil(data.total / data.size);
    $('pagination').hidden = pages <= 1;
    $('page-number').textContent = `第 ${data.page} / ${pages} 页`;
    $('previous').disabled = data.page <= 1;
    $('next').disabled = data.page >= pages;
  } catch (error) {
    if (id !== state.request) return;
    status.classList.add('error');
    status.replaceChildren(document.createTextNode(`${error.message} `), button('重试', () => loadCatalog(page)));
  }
}

async function showProduct(summary) {
  const mount = openPanel(summary.name);
  const active = state.panel;
  mount.append(element('p', '正在读取规格…', 'muted'));
  try {
    const product = await api(`/api/v1/products/${summary.productId}`);
    if (active !== state.panel || !$('panel').open) return;
    const facts = element('dl', null, 'details-grid');
    for (const [label, value] of [['品牌', product.brand], ['适用', product.usageText], ['储存', product.storage]]) {
      if (value) facts.append(element('dt', label), element('dd', value));
    }
    const skus = product.skus.filter((sku) => validId(sku.skuId) && Number.isSafeInteger(sku.priceFen) && sku.priceFen >= 0);
    mount.replaceChildren(facts);
    if (skus.length === 0) return mount.append(element('p', '暂无可选择的规格。', 'message'));
    const form = element('form', null, 'panel-form');
    const choice = element('select');
    for (const sku of skus) {
      const weight = Number.isFinite(sku.weightGram) ? ` · ${sku.weightGram / 1000} kg` : '';
      const option = element('option', `${sku.specName}${weight} · ${money(sku.priceFen)}`);
      option.value = sku.skuId;
      choice.append(option);
    }
    const quantity = quantityInput(1);
    const stock = element('p', '', 'muted');
    const updateStock = () => {
      const selected = skus.find((sku) => sku.skuId === choice.value);
      stock.textContent = selected.estimatedAvailable == null ? '库存将在结算时确认。' : `参考库存 ${selected.estimatedAvailable} 袋`;
    };
    choice.addEventListener('change', updateStock);
    updateStock();
    const add = element('button', '加入购物袋', 'button primary');
    add.type = 'submit';
    const alert = element('p', '', 'error');
    alert.setAttribute('role', 'alert');
    form.append(field('包装规格', choice), field('数量（袋）', quantity), stock, add, alert);
    form.addEventListener('submit', (event) => {
      event.preventDefault();
      const selected = skus.find((sku) => sku.skuId === choice.value);
      const number = quantity.valueAsNumber;
      const row = state.cart.find((item) => item.skuId === selected.skuId);
      if (!Number.isInteger(number) || number < 1 || number + (row?.quantity ?? 0) > 100) {
        alert.textContent = '同一规格最多选购 100 袋。';
        return;
      }
      if (!row && state.cart.length >= 20) { alert.textContent = '最多可选择 20 种规格。'; return; }
      if (row) Object.assign(row, { quantity: row.quantity + number, priceFen: selected.priceFen });
      else state.cart.push({ skuId: selected.skuId, name: product.name, spec: selected.specName, quantity: number, priceFen: selected.priceFen });
      saveCart();
      showCart();
    });
    mount.append(form);
  } catch (error) {
    if (active === state.panel && $('panel').open) mount.replaceChildren(errorNode(error));
  }
}

function field(label, input) {
  const wrapper = element('label', label);
  wrapper.append(input);
  return wrapper;
}

function quantityInput(value) {
  const input = element('input');
  Object.assign(input, { type: 'number', min: '1', max: '100', step: '1', value, required: true });
  return input;
}

function showCart(checkout = false) {
  const mount = openPanel(checkout ? '确认采购清单' : '购物袋');
  if (state.cart.length === 0) return mount.append(element('p', '购物袋为空，请先选择面粉规格。', 'message'));
  const list = element('ul', null, 'cart-list');
  const total = element('strong');
  const refreshTotal = () => { total.textContent = money(state.cart.reduce((sum, item) => sum + item.quantity * item.priceFen, 0)); };
  for (const item of state.cart) {
    const title = element('div');
    title.append(element('strong', item.name), element('p', `${item.spec} · ${money(item.priceFen)} / 袋`, 'muted'));
    const row = element('li', null, 'cart-row');
    const input = quantityInput(item.quantity);
    input.addEventListener('change', () => {
      if (!input.checkValidity()) { input.value = item.quantity; return; }
      item.quantity = input.valueAsNumber;
      saveCart();
      refreshTotal();
    });
    row.append(title, field('袋数', input), button('移除', () => {
      state.cart = state.cart.filter((entry) => entry.skuId !== item.skuId);
      saveCart();
      showCart(checkout);
    }));
    list.append(row);
  }
  const sum = element('div', null, 'cart-total');
  sum.append(element('span', '商品金额预估（未含运费）'), total);
  refreshTotal();
  mount.append(list, sum);
  if (!checkout) return mount.append(button('去结算', () => showCart(true), 'button primary'));
  if (!state.account) mount.append(button('登录后继续', () => showAccount(true), 'button primary'));
  const submit = button('提交订单并付款', () => {}, 'button primary');
  submit.disabled = true;
  mount.append(element('p', '收货地址、订单和支付服务尚未开放，当前可核对购物清单。', 'message'), submit);
}

function showAccount(backToCheckout = false) {
  const mount = openPanel(state.account ? '我的账户' : '登录');
  const active = state.panel;
  if (state.account) {
    mount.append(element('p', `当前账号 ${state.account.username}`), button('退出登录', async () => {
      const { refreshToken } = state.account;
      state.account = null;
      $('account').textContent = '登录';
      try { await post('/api/auth/logout', { refreshToken }); }
      catch (error) { if (active === state.panel && $('panel').open) mount.replaceChildren(errorNode(error)); return; }
      if (active === state.panel && $('panel').open) showAccount(backToCheckout);
    }));
    return;
  }
  const form = element('form', null, 'panel-form');
  const username = element('input');
  Object.assign(username, { required: true, autocomplete: 'username', maxLength: 32 });
  const password = element('input');
  Object.assign(password, { required: true, type: 'password', autocomplete: 'current-password', maxLength: 72 });
  const submit = element('button', '登录', 'button primary');
  submit.type = 'submit';
  const message = element('div');
  message.setAttribute('role', 'alert');
  form.append(field('用户名', username), field('密码', password), submit, message);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    submit.disabled = true;
    message.replaceChildren();
    try {
      state.account = await post('/api/auth/login', { username: username.value.trim(), password: password.value });
      $('account').textContent = state.account.username;
      if (active === state.panel && $('panel').open) backToCheckout ? showCart(true) : showAccount();
    } catch (error) { message.replaceChildren(errorNode(error)); }
    finally { submit.disabled = false; password.value = ''; }
  });
  mount.append(form);
}

function post(path, data) {
  return api(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) });
}

$('filters').addEventListener('submit', (event) => { event.preventDefault(); loadCatalog(); });
$('previous').addEventListener('click', () => loadCatalog(state.page - 1));
$('next').addEventListener('click', () => loadCatalog(state.page + 1));
$('bag-open').addEventListener('click', () => showCart());
$('account').addEventListener('click', () => showAccount());
$('panel-close').addEventListener('click', () => $('panel').close());
updateCartCount();
loadCategories();
loadCatalog();
