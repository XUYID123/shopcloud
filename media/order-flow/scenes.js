window.OrderScenes = (() => {
'use strict';
const { ss, lerp } = VK;
const P = Props;
const { ink, paper, cyan, amber, red, muted, money, example,
    label, heading, annotation, floor, flour, receipt, stamp, phone, shelf, envelope, parcel } = P;
const amount = '¥' + money(example.payable);
const order = 'DEMO-071';

function paperOrder(fr, x, y, status, tint = cyan, scale = 1) {
    fr.g({ at: [x, y], s: scale }, () => receipt(fr, '订单小票',
        [['订单号', order], ['商品', '小麦粉 × 2'], ['应付', amount]], status, tint));
}

function bag(fr, t) {
    heading(fr, '网页购物袋', 'SHOP-WEB');
    const slide = ss(t, 15.06, 16.45);
    const inspect = ss(t, 19.75, 20.68);
    const x = lerp(1095, 1000, inspect);
    fr.g({ at: [x, 444], s: 1.04 }, () => {
        phone(fr, inspect < .5 ? '购物袋' : '确认采购清单',
            [['商品', '小麦粉'], ['规格', '5 kg / 袋'], ['数量', slide < .94 ? '1 袋' : '2 袋'],
                ['商品预估', slide < .94 ? '¥30.00' : '¥60.00']],
            inspect < .5 ? '去结算' : '提交订单并付款', inspect < .5 ? cyan : '#9da8ae');
    });
    shelf(fr, 590, 625, 420);
    flour(fr, 525, 496, .69);
    fr.g({ a: 1 - ss(slide, .70, 1) }, () => flour(fr, lerp(665, 975, slide), lerp(496, 408, slide), lerp(.69, .20, slide)));
    annotation(fr, t < 24.45 ? '浏览器本地保存 · 商品与金额为示例' : '接下来 · 已启用的后端交易 API');
}

function address(fr, x, y, scale, alpha) {
    fr.g({ at: [x, y], s: scale, a: alpha }, () => {
        fr.rect(-205, -111, 410, 222, { r: 20, fill: '#dfd3b9', color: '#f2e6c8', w: 2 });
        fr.ellipse(-138, -21, 32, 33, { fill: '#819297' });
        fr.path('M-182 62 Q-182 19 -138 19 Q-94 19 -94 62 Z', { fill: '#819297' });
        label(fr, '本人收货地址', -76, -49, 30, ink, 'l', 'sans');
        label(fr, '示例 · 上海市', -76, 4, 26, '#46545a', 'l');
        label(fr, '归属与配送区已核对', -76, 55, 21, '#46545a', 'l');
    });
}

function hourglass(fr, x, y, p) {
    fr.g({ at: [x, y], rot: (1 - p) * Math.PI, a: p }, () => {
        floor(fr, 0, 128, 119, 14);
        fr.path('M-72 -91 Q-65 -39 -14 -8 Q-5 0 -14 8 Q-65 37 -72 91 L72 91 Q65 37 14 8 Q5 0 14 -8 Q65 -39 72 -91 Z',
            { color: '#b6dbe4', w: 4, fill: '#203746' });
        fr.path('M-61 -68 Q-4 -55 61 -68 Q45 -28 0 -9 Q-39 -23 -61 -68 Z M-55 81 Q-7 12 55 81 Z',
            { fill: '#d8b97c' });
        fr.line(0, 0, 0, 44, { w: 2, color: '#d8b97c' });
        fr.rect(-88, -105, 176, 14, { r: 5, fill: '#718b98' });
        fr.rect(-88, 91, 176, 14, { r: 5, fill: '#718b98' });
    });
}

function quote(fr, t) {
    heading(fr, '结算报价', 'CHECKOUT QUOTE');
    const card = ss(t, 30.3, 31.18);
    const product = ss(t, 34.95, 35.9);
    const fee = ss(t, 39.6, 40.25);
    const clock = ss(t, 44.25, 45.12);
    fr.g({ at: [971, 452] }, () => {
        fr.rect(-224, -260, 448, 520, { r: 15, fill: '#324553', color: '#6f8ca0', w: 3 });
        fr.rect(-83, -274, 166, 40, { r: 8, fill: '#84919a', color: '#a4b5bd', w: 2 });
        if (product > 0) {
            const lines = [['单价', '¥' + money(example.unitPrice)], ['袋数 / 重量', '2 袋 / 10 kg'],
                ['商品金额', '¥' + money(example.goods)]];
            if (fee > .1) lines.push(['运费', '¥' + money(example.freight)]);
            fr.g({ a: product, at: [0, lerp(-35, 15, product)], s: 1.15 }, () =>
                receipt(fr, '结算报价', lines, fee > .43 ? amount : '', amber));
        } else {
            label(fr, '等待商品报价', 0, 113, 29, muted);
        }
    });
    address(fr, lerp(352, 574, card), lerp(451, 366, card), lerp(1, .91, product), card);
    fr.g({ a: product, at: [0, (1 - product) * -82] }, () => {
        shelf(fr, 1364, 634, 260);
        flour(fr, 1364, 483, .82);
    });
    hourglass(fr, 1624, 448, clock);
    fr.g({ a: clock }, () => label(fr, '5 分钟', 1624, 593, 44, amber, 'c', 'serifh'));
    annotation(fr, t < 34.95 ? 'POST /api/v1/checkout/quotes  ·  shop-user'
        : t < 39.6 ? '实时 SKU 价格与可售状态  ·  shop-product'
        : '运费与应付由服务端计算 · 商品与金额为示例');
}

function drawer(fr, open, key) {
    fr.g({ at: [1020, 573] }, () => {
        floor(fr, 0, 156, 330, 28);
        fr.path('M-280 -80 L-209 -137 L241 -137 L280 -80 V118 H-280 Z', { fill: '#263b4a', color: '#608298', w: 3 });
        fr.poly([[-279, -80], [-207, -134], [240, -134], [280, -80], [-279, -80]], { fill: '#364c5a' });
        fr.rect(-261, -64, 522, 158, { r: 6, color: '#567184', w: 2, fill: '#172831' });
        fr.g({ at: [open * -44, open * 67] }, () => {
            fr.rect(-240, -49, 480, 118, { r: 8, fill: '#314b5a', color: '#668496', w: 2 });
            fr.rect(-42, -42, 84, 12, { r: 4, fill: '#86a2ae' });
            label(fr, '同一事务', 0, 20, 32, paper);
            fr.circle(170, 9, 27, { color: cyan, w: 2, fill: '#182632' });
            fr.path('M168 0 H173 L177 13 H164 Z', { fill: '#a5bfca' });
        });
        if (key > 0) {
            fr.g({ at: [lerp(335, 231, key), 11], rot: -.1 * key }, () => {
                fr.arc(31, 0, 22, 0, Math.PI * 2, { color: '#b4bbbc', w: 10 });
                fr.path('M11 -6 H-71 L-82 3 L-71 12 H-55 V4 H-43 V12 H-32 V5 H11 Z', { fill: '#bdc4c5' });
            });
        }
    });
}

function place(fr, t) {
    heading(fr, '提交订单', 'PLACE ORDER');
    const verify = ss(t, 50.1, 50.96);
    const writing = ss(t, 54.75, 56.15);
    const key = ss(t, 59.4, 60.35);
    const delivery = ss(t, 64.05, 65.55);
    const line = [['SKU / 数量', '小麦粉 × 2'], ['地址归属', '本人'], ['应付', amount]];
    fr.g({ at: [554, 442], s: .94 }, () => receipt(fr, '原结算报价', line, writing > .5 ? '已使用' : '有效', writing > .5 ? amber : cyan));
    fr.g({ a: verify * (1 - writing), at: [lerp(1115, 890, verify), 444], s: .94 },
        () => receipt(fr, '重新核对', line, '版本一致', amber));
    if (writing > 0) {
        const tuck = ss(t, 55.6, 56.25);
        drawer(fr, writing * (1 - tuck), key);
        fr.g({ a: 1 - tuck, at: [1070, lerp(335, 564, writing)], s: lerp(.56, .34, tuck) },
            () => receipt(fr, '订单小票', [['订单号', order]], 'PREPARING', cyan));
        fr.g({ a: 1 - tuck }, () => envelope(fr, 1220, lerp(372, 571, writing), '预占指令', cyan, .55));
        fr.g({ a: tuck }, () => paperOrder(fr, 1470, 403, 'PREPARING', cyan, .92));
    }
    if (delivery > 0) {
        envelope(fr, lerp(1020, 1605, delivery), lerp(564, 649, delivery) - Math.sin(Math.PI * delivery) * 87,
            '预占指令', cyan, .8);
    }
    annotation(fr, t < 54.75 ? 'POST /api/v1/orders' : t < 59.4 ? 'MySQL · 订单 + 报价核销 + 事务 Outbox'
        : t < 64.05 ? 'Idempotency-Key · 同用户与同报价' : 'RocketMQ · 发往 shop-inventory');
}

function clock(fr, x, y, remainder, size, tint = cyan) {
    fr.g({ at: [x, y], s: size }, () => {
        fr.rect(-23, -116, 46, 15, { r: 4, color: '#768f9f', w: 3, fill: '#2a404c' });
        fr.circle(0, 0, 104, { color: tint, w: 3, fill: '#172632' });
        for (let i = 0; i < 12; i += 1) {
            const angle = i * Math.PI / 6;
            fr.line(89 * Math.sin(angle), 89 * Math.cos(angle), 97 * Math.sin(angle), 97 * Math.cos(angle), { color: tint, w: 2, a: .7 });
        }
        label(fr, remainder, 0, -9, 50, tint, 'c', 'mono');
        label(fr, '支付时限', 0, 45, 23, muted);
    });
}

function pallet(fr, x, y, tint, name) {
    fr.g({ at: [x, y] }, () => {
        shelf(fr, 0, 0, 426);
        fr.rect(-179, -1, 358, 26, { r: 4, color: tint, w: 2, fill: '#1b3441' });
        label(fr, name, 0, 12, 19, tint, 'c', 'mono');
    });
}

function warehouse(fr, t) {
    heading(fr, '仓库预占', 'INVENTORY RESERVATION');
    const pickA = ss(t, 74.65, 75.75);
    const pickB = ss(t, 75.88, 77.03);
    const reply = ss(t, 79.3, 80.32);
    const shortage = ss(t, 83.95, 84.61);
    const lit = ss(t, 70, 70.7);
    const reserved = pickB >= 1 ? 2 : 0;
    fr.g({ a: 1 - shortage }, () => {
        shelf(fr, 530, 626, 718);
        pallet(fr, 1222, 627, cyan, '预留台 · ' + order);
        label(fr, '先到期 · 批次 A', 342, 274, 29, cyan);
        label(fr, '后到期 · 批次 B', 692, 274, 29, muted);
        fr.line(237, 299, 447, 299, { color: cyan, w: 3, a: lit * .7 });
        flour(fr, 625, 479, .83, '12 / 24');
        flour(fr, 782, 479, .83, '12 / 24');
        flour(fr, lerp(264, 1130, pickA), 479 - Math.sin(Math.PI * pickA) * 93, .83, '12 / 10');
        flour(fr, lerp(421, 1309, pickB), 479 - Math.sin(Math.PI * pickB) * 68, .83, '12 / 10');
        label(fr, '可售 ' + (4 - reserved), 530, 720, 32, paper, 'c', 'mono');
        label(fr, '预留 ' + reserved, 1222, 720, 32, cyan, 'c', 'mono');
        paperOrder(fr, 1640, 413, reply > .56 ? 'UNPAID' : 'PREPARING', cyan, .78);
        fr.g({ a: reply }, () => clock(fr, 1640, 640, t < 81.35 ? '15:00' : t < 82.35 ? '14:59' : '14:58', .72));
        if (reply > 0 && reply < 1) {
            fr.g({ a: 1 - ss(reply, .65, 1) }, () => envelope(fr, lerp(1288, 1640, reply), 247, '预占回执', cyan, .69));
        }
    });
    fr.g({ a: shortage }, () => {
        shelf(fr, 580, 626, 466);
        pallet(fr, 1122, 627, cyan, '预留台 · 空');
        flour(fr, 580, 479, .83, '12 / 10');
        paperOrder(fr, 1600, 492, 'FAILED', red, .9);
        label(fr, '需要 2 袋 · 只剩 1 袋', 580, 720, 30, red);
        label(fr, '预留 0', 1122, 720, 30, cyan, 'c', 'mono');
    });
    annotation(fr, t < 74.65 ? 'FEFO · 可售且未过期 · 批次效期为示例'
        : t < 79.3 ? '库存数量为示例' : t < 83.95 ? 'UNPAID / RESERVED · 支付时限从回执入单开始' : '缺货分支 · STOCK_NOT_ENOUGH');
}

function terminal(fr, x, y, verified) {
    fr.g({ at: [x, y] }, () => {
        floor(fr, 0, 226, 204, 28);
        fr.path('M-155 -180 Q-155 -205 -125 -205 H114 Q142 -205 145 -177 L164 168 Q164 199 136 201 H-140 Q-167 199 -167 168 Z', {
            fill: fr.grad(-160, -170, 135, 210, [[0, '#506b79'], [.55, '#304959'], [1, '#213340']]),
            color: '#7e9dad', w: 3
        });
        fr.rect(-123, -157, 241, 141, { r: 14, fill: '#152c38', color: '#7999a5', w: 2 });
        label(fr, 'MOCK PAY', 0, -127, 22, muted, 'c', 'mono', 2);
        label(fr, amount, 0, -88, 40, verified ? amber : cyan, 'c', 'mono');
        label(fr, verified ? '回调验签通过' : '等待签名回调', 0, -44, 21, paper);
        fr.rect(-90, 20, 175, 17, { r: 4, fill: '#172835', color: '#7897a6', w: 2 });
        for (let i = 0; i < 12; i += 1) {
            fr.rect(-77 + (i % 3) * 53, 72 + Math.floor(i / 3) * 27, 44, 19,
                { r: 4, fill: i === 11 ? '#355d68' : '#415968', color: '#617e8b', w: 1 });
        }
    });
}

function pay(fr, t) {
    heading(fr, '模拟支付', 'MOCK PAYMENT');
    const session = ss(t, 90, 90.9);
    const check = ss(t, 94.6, 95.87);
    const result = ss(t, 99.25, 100.08);
    const confirm = ss(t, 101.1, 102.6);
    terminal(fr, 667, 467, check > .83);
    paperOrder(fr, 1120, 447, result > .48 ? 'PAID' : 'UNPAID', result > .48 ? amber : cyan);
    fr.g({ a: session * (1 - check), at: [lerp(637, 417, session), 410], s: .68, rot: -.08 },
        () => receipt(fr, '支付会话', [['金额', amount], ['币种', 'CNY']], 'MOCK', cyan));
    if (check > 0 && result < 1) envelope(fr, lerp(416, 666, check), lerp(578, 487, check), '签名回执', amber, .7 * (1 - .45 * check));
    if (result > 0) fr.g({ a: 1 - ss(t, 100.08, 100.65) }, () =>
        envelope(fr, lerp(668, 1098, result), lerp(692, 655, result), '支付成功', amber, .67));
    const stockX = lerp(1515, 1645, confirm);
    pallet(fr, stockX, 626, confirm > .5 ? amber : cyan, confirm > .5 ? '扣减已确认' : '订单预留');
    flour(fr, stockX - 70, 495, .72);
    flour(fr, stockX + 70, 495, .72);
    annotation(fr, t < 94.6 ? 'POST /api/v1/payment-sessions'
        : t < 99.25 ? '回调包含订单、金额与币种'
        : confirm < 1 ? 'RocketMQ · 支付成功后发出库存确认指令' : 'PAID / SUCCEEDED / CONFIRMED');
}

function close(fr, t) {
    heading(fr, '未付时的另一条路', 'CANCELLATION / TIMEOUT');
    const expire = ss(t, 110, 111.12);
    const closed = ss(t, 114.65, 115.57);
    const back = ss(t, 116.04, 117.92);
    const refund = ss(t, 119.3, 121.07);
    shelf(fr, 509, 629, 410);
    pallet(fr, 1010, 629, cyan, '订单预留');
    flour(fr, lerp(936, 444, back), 485 - 91 * Math.sin(Math.PI * back), .81, '12 / 10');
    flour(fr, lerp(1080, 582, back), 485 - 71 * Math.sin(Math.PI * back), .81, '12 / 10');
    paperOrder(fr, 1450, 509, closed > .5 ? 'CLOSED' : expire > .48 ? 'CLOSING' : 'UNPAID', expire > .48 ? red : cyan, .91);
    if (closed < 1) fr.g({ a: 1 - closed }, () => clock(fr, 440, 300,
        expire < .3 ? '00:02' : expire < .7 ? '00:01' : '00:00', .72, expire > .7 ? red : cyan));
    fr.g({ a: closed * (1 - refund) }, () => envelope(fr, lerp(1582, 1185, closed), 287, '关闭确认', red, .75));
    fr.g({ a: refund }, () => envelope(fr, lerp(1445, 1020, refund), 305 - 51 * Math.sin(Math.PI * refund), '原额退回', red, .86));
    annotation(fr, t < 114.65 ? '取消申请或付款超时'
        : t < 119.3 ? '释放预留 · 批次仍可售时回到可售库存' : '迟到支付 · 订单保持 CLOSED');
}

function doorstep(fr, x, y, alpha) {
    fr.g({ at: [x, y], a: alpha }, () => {
        fr.rect(-189, -346, 378, 563, { fill: '#253643', color: '#8196a2', w: 3 });
        fr.rect(-147, -313, 292, 514, { fill: '#314957', color: '#627c8b', w: 2 });
        fr.rect(-113, -275, 223, 187, { fill: '#142633', color: '#627c8b', w: 3 });
        fr.line(0, -275, 0, -88, { color: '#79909e', w: 5 });
        fr.line(-113, -181, 110, -181, { color: '#79909e', w: 5 });
        fr.rect(97, -36, 28, 13, { r: 5, fill: '#a2b4ba' });
        fr.poly([[-191, 214], [189, 214], [232, 245], [-232, 245], [-191, 214]], { fill: '#3e515e' });
    });
}

function ship(fr, t) {
    heading(fr, '发货与签收', 'FULFILLMENT');
    const tag = ss(t, 125, 126.4);
    const arrival = ss(t, 129.65, 130.55);
    const done = ss(t, 130.72, 131.45);
    doorstep(fr, 1360, 498, arrival);
    fr.g({ a: 1 - arrival }, () => shelf(fr, 1086, 661, 695));
    paperOrder(fr, 583, 447, done > .5 ? 'COMPLETED' : tag > .8 ? 'SHIPPED' : 'PAID', amber);
    parcel(fr, lerp(1086, 1351, arrival), lerp(513, 638, arrival), lerp(1.06, .71, arrival), tag > .76);
    fr.g({ a: 1 - ss(tag, .64, .77), at: [lerp(1450, 1086, tag), lerp(322, 545, tag)], s: .52, rot: -.13 * (1 - tag) },
        () => receipt(fr, '物流运单', [['订单号', order], ['批次', 'A · 2 袋']], '待贴单', amber));
    annotation(fr, t < 129.65 ? '管理员 · 核对可发批次与数量' : 'PUT /api/v1/orders/{orderNo}/receipt');
}

return { bag, quote, place, warehouse, pay, close, ship };
})();
