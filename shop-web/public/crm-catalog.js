const element = (id) => document.getElementById(id);
const filters = element('filters');
const categories = element('categories');
const products = element('products');
const summary = element('summary');
const previous = element('previous');
const next = element('next');
const pagination = element('pagination');
const money = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' });
const selections = new Map();
let facetSignature;
let keyword = '';
let page = 1;
let request;

function node(tag, text, className) {
  const item = document.createElement(tag);
  if (text != null) item.textContent = text;
  if (className) item.className = className;
  return item;
}

function card(product) {
  const article = node('article', null, 'product');
  const copy = node('div', null, 'product-copy');
  const picture = node('div', null, 'product-picture crm-picture');
  if (product.imageUrl?.startsWith('https://crm.wudeli.com/img/')) {
    const image = node('img');
    image.src = product.imageUrl;
    image.alt = product.name;
    image.loading = 'lazy';
    image.addEventListener('error', () => image.remove());
    picture.append(image);
  }
  copy.append(node('h3', product.name));
  const attributes = [product.brand, product.usage].filter(Boolean);
  if (attributes.length) copy.append(node('p', attributes.join(' · '), 'muted'));

  const details = node('details');
  const list = node('ul', null, 'crm-skus');
  for (const sku of product.skus) {
    const row = node('li');
    const price = sku.priceFen == null ? '未提供报价' : money.format(sku.priceFen / 100);
    row.append(node('strong', sku.name),
      node('span', [sku.specName, `货号 ${sku.skuCode}`, price].filter(Boolean).join(' · ')));
    list.append(row);
  }
  details.append(node('summary', `查看 ${product.skus.length} 个 SKU · ${product.spuCode}`), list);
  copy.append(details);
  article.append(picture, copy);
  return article;
}

function productCount({ total, skuCount }) {
  return `${total} 个 SPU，${skuCount} 个 SKU`;
}

function selectFacet(key, value) {
  if (value) selections.set(key, value);
  else selections.delete(key);
  page = 1;
  load();
}

function categorySection(group) {
  const { name, items } = group;
  const section = node('section', null, 'crm-group');
  section.setAttribute('aria-label', name);
  const heading = node('div', null, 'catalog-heading');
  const title = node('div');
  title.append(node('h2', name), node('p', productCount(group), 'muted'));
  heading.append(title);

  if (!selections.has('category') && group.total > items.length) {
    const view = node('button', `查看全部${name}`, 'plain');
    view.type = 'button';
    view.addEventListener('click', () => {
      selectFacet('category', name);
      categories.querySelector('[data-facet="category"] [aria-pressed="true"]')?.focus();
    });
    heading.append(view);
  }

  const cards = node('div', null, 'product-grid crm-products');
  cards.append(...items.map(card));
  section.append(heading, cards);
  return section;
}

function buildFacets(facets) {
  const signature = JSON.stringify(facets);
  if (signature === facetSignature) return;
  facetSignature = signature;
  categories.replaceChildren();
  for (const { key, label, options } of facets) {
    const row = node('div', null, 'crm-facet');
    row.dataset.facet = key;
    row.setAttribute('role', 'group');
    row.setAttribute('aria-label', label);
    const choices = node('div', null, 'crm-facet-choices');
    for (const value of ['', ...options]) {
      const button = node('button', value || '全部', 'crm-facet-choice');
      button.type = 'button';
      button.value = value;
      button.addEventListener('click', () => selectFacet(key, value));
      choices.append(button);
    }
    row.append(node('span', label, 'crm-facet-label'), choices);
    categories.append(row);
  }
}

function markFacets() {
  for (const row of categories.children) {
    const selected = selections.get(row.dataset.facet) ?? '';
    for (const button of row.querySelectorAll('button')) {
      button.setAttribute('aria-pressed', button.value === selected);
    }
  }
}

async function load() {
  request?.abort();
  const controller = new AbortController();
  request = controller;
  markFacets();
  pagination.hidden = true;
  products.replaceChildren();
  summary.textContent = '正在从商品库读取…';

  const activeCategory = selections.get('category');
  const path = activeCategory ? '/api/v1/products/crm-catalog' : '/api/v1/products/crm-catalog/overview';
  const params = new URLSearchParams(selections);
  if (keyword) params.set('keyword', keyword);
  if (activeCategory) params.set('page', page);
  const query = params.size ? `?${params}` : '';
  try {
    const result = await fetch(path + query, { signal: controller.signal });
    if (controller.signal.aborted) return;
    if (result.status === 404) {
      summary.textContent = '本机尚未启用 CRM 目录预览。';
      return;
    }
    const payload = await result.json();
    if (controller.signal.aborted) return;
    if (!result.ok || payload.code !== 'OK') throw new Error('CRM 目录读取失败');
    const catalog = payload.data;
    if (catalog === null) {
      summary.textContent = '商品库暂无 CRM 目录。';
      return;
    }

    element('source').textContent = `${catalog.company} · ${catalog.capturedOn} · SPU 按商品资料归组 · 只读预览`;
    if (catalog.categories) {
      buildFacets(catalog.facets ?? [{ key: 'category', label: '品类', options: catalog.categories }]);
    }
    markFacets();
    filters.hidden = false;
    categories.hidden = false;
    summary.textContent = catalog.total ? productCount(catalog) : '没有匹配的商品';
    if (!activeCategory) {
      products.replaceChildren(...catalog.groups.map(categorySection));
      return;
    }
    if (!catalog.total) return;
    const pages = Math.ceil(catalog.total / catalog.size);
    if (catalog.page > pages) {
      page = pages;
      await load();
      return;
    }
    products.replaceChildren(categorySection({ name: activeCategory, ...catalog }));
    pagination.hidden = pages < 2;
    element('page-number').textContent = `第 ${catalog.page} / ${pages} 页`;
    previous.disabled = catalog.page === 1;
    next.disabled = catalog.page === pages;
  } catch {
    if (!controller.signal.aborted) summary.textContent = 'CRM 目录读取失败，请稍后重试。';
  }
}

filters.addEventListener('submit', (event) => {
  event.preventDefault();
  keyword = element('keyword').value.trim();
  page = 1;
  load();
});
previous.addEventListener('click', () => { page -= 1; load(); });
next.addEventListener('click', () => { page += 1; load(); });

load();
