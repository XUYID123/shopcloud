(() => {
'use strict';
const { ss, lerp } = VK;
const { paper, cyan, amber, label, receipt, flour, shelf, parcel, money, example } = Props;
const subtitles = [
    { a: 0, b: 4.1, zh: "订单号到手了，仓库里的两袋面粉还在原处。" },
    { a: 4.35, b: 8.9, zh: "要等仓库回信，订单才会进入待支付。" },
    { a: 15, b: 19.5, zh: "现在的商城网页，可以把面粉放进本地购物袋。" },
    { a: 19.75, b: 24.2, zh: "采购清单可以核对，提交按钮还是灰的。" },
    { a: 24.45, b: 29, zh: "下面这笔订单，从已启用的后端接口走。" },
    { a: 30.3, b: 34.7, zh: "报价前，订单服务先找用户服务核对本人地址。" },
    { a: 34.95, b: 39.35, zh: "再找商品服务确认可售，按实时价格开单。" },
    { a: 39.6, b: 44, zh: "两袋共十公斤，示例商品价六十元，运费十元。" },
    { a: 44.25, b: 48.7, zh: "这张报价单只有五分钟，过期要重新报价。" },
    { a: 50.1, b: 54.5, zh: "提交报价号，服务端会重核地址和商品版本。" },
    { a: 54.75, b: 59.15, zh: "订单和预占指令一起落库，报价也在同一事务用掉。" },
    { a: 59.4, b: 63.8, zh: "带同一个幂等键重试，会拿到同一张订单。" },
    { a: 64.05, b: 68.55, zh: "订单先显示准备中，发件箱再把预占指令送出。" },
    { a: 70, b: 74.4, zh: "仓库从合格批次里，先挑最早到期的面粉。" },
    { a: 74.65, b: 79.05, zh: "两袋从可售货架移到这张订单的预留托盘。" },
    { a: 79.3, b: 83.7, zh: "回执一到，订单变成待支付，才开始倒计时。" },
    { a: 83.95, b: 88.45, zh: "如果仓库凑不齐，订单会标记失败。" },
    { a: 90, b: 94.35, zh: "回到成功那单，买家发起 Mock 支付。" },
    { a: 94.6, b: 99, zh: "支付服务验签，核对订单、金额和币种。" },
    { a: 99.25, b: 103.65, zh: "订单收到支付成功消息，再叫仓库确认扣减。" },
    { a: 103.9, b: 108.4, zh: "支付确认和库存确认都齐了，才允许登记发货。" },
    { a: 110, b: 114.4, zh: "买家取消或逾期未付，会进入关单流程。" },
    { a: 114.65, b: 119.05, zh: "支付侧确认关闭后，订单关单，再通知仓库释放。" },
    { a: 119.3, b: 123.7, zh: "关单后才到的成功付款，会走全额退款。" },
    { a: 125, b: 129.4, zh: "已支付的那单，核对可发批次后贴上运单。" },
    { a: 129.65, b: 134.05, zh: "买家确认收货，原来的订单就完成了。" },
    { a: 134.3, b: 138.1, zh: "开头那张订单，现在从准备中走到了已完成。" },
];
const audio = [
    [0.5, 'soft'], [10.4, 'bloom'], [15.35, 'soft'], [20, 'tick'], [30.65, 'tock'],
    [35.15, 'soft'], [40, 'tick'], [44.85, 'shimmer'], [50.45, 'soft'], [55.35, 'thud'],
    [59.75, 'tick'], [64.45, 'rise'], [70.2, 'tick'], [75, 'soft'], [76.1, 'tock'],
    [79.6, 'stamp'], [84.4, 'thud'], [90.35, 'soft'], [95.05, 'tick'], [99.7, 'stamp'],
    [102, 'tock'], [110.4, 'thud'], [115, 'stamp'], [117.2, 'tock'], [119.75, 'rise'],
    [125.7, 'soft'], [130.25, 'stamp'], [138.6, 'chord']
];

function backlight(fr, t) {
    fr.look.bgo = { stars: .10, core: .78 };
    fr.look.under = ctx => {
        const glow = ctx.createRadialGradient(957, 670, 60, 957, 670, 940);
        glow.addColorStop(0, 'rgba(46,77,89,.38)');
        glow.addColorStop(1, 'rgba(14,22,31,0)');
        ctx.fillStyle = glow;
        ctx.fillRect(0, 0, 1920, 880);
        ctx.fillStyle = '#101824';
        ctx.fillRect(0, 828, 1920, 1);
    };
}

function opening(fr, t, finished = false) {
    const entry = finished ? 1 : ss(t, 0.08, 1.6);
    Props.heading(fr, '同一张订单', 'ORDER JOURNEY');
    fr.g({ at: [660, 438], s: 1.14 }, () => {
        Props.phone(fr, '订单详情', [
            ['订单号', 'DEMO-071'],
            ['库存', finished ? '扣减已确认' : '等待仓库回执']
        ], finished ? '已完成' : '准备中', finished ? amber : cyan);
    });
    fr.g({ a: entry, at: [lerp(755, 1044, entry), 470], rot: -.09 }, () => {
        receipt(fr, '订单小票', [['订单号', 'DEMO-071'], ['应付', '¥' + money(example.payable)]],
            finished ? 'COMPLETED' : 'PREPARING', finished ? amber : cyan);
    });
    shelf(fr, 1545, 625, 418);
    if (finished) parcel(fr, 1545, 520, .75, true);
    else {
        flour(fr, 1480, 465, .87);
        flour(fr, 1627, 465, .87);
    }
}

function title(fr, t, end) {
    const alpha = end ? ss(t, 138, 139.1) : ss(t, 9, 9.85) * (1 - ss(t, 14.2, 15.1));
    fr.g({ a: alpha }, () => {
        label(fr, end ? '实现依据' : 'SPRINGCLOUD-DEMO', 960, 295, 28, cyan, 'c', 'mono', 5);
        label(fr, '一单面粉', 960, 481, 153, paper, 'c', 'serifl', 21);
        fr.line(906, 570, 1014, 570, { color: cyan, w: 2 });
        label(fr, end ? 'shop-order / shop-inventory / shop-pay / shop-web' : '一张订单，从受理到签收', 960, 643, 32, paper);
        if (end) label(fr, '当前工作树 · 2026-11-24 · 示例交易', 960, 706, 26, '#a3b7c8');
    });
}

const scenes = [
    { from: 0, to: 9.7, draw: opening },
    { from: 14.3, to: 30.4, draw: (fr, t) => OrderScenes.bag(fr, t) },
    { from: 29.6, to: 50.4, draw: (fr, t) => OrderScenes.quote(fr, t) },
    { from: 49.6, to: 70, draw: (fr, t) => OrderScenes.place(fr, t) },
    { from: 69.25, to: 90.05, draw: (fr, t) => OrderScenes.warehouse(fr, t) },
    { from: 89.35, to: 110.05, draw: (fr, t) => OrderScenes.pay(fr, t) },
    { from: 109.35, to: 125.05, draw: (fr, t) => OrderScenes.close(fr, t) },
    { from: 124.3, to: 134.65, draw: (fr, t) => OrderScenes.ship(fr, t) },
    { from: 133.95, to: 138.9, draw: (fr, t) => opening(fr, t, true) }
];

VK.film({
    dur: 144,
    theme: 'night',
    bloom: .14,
    grain: .018,
    meta: { title: '一单面粉' },
    draw(fr, t) {
        backlight(fr, t);
        for (const scene of scenes) {
            if (t < scene.from || t >= scene.to) continue;
            const alpha = (scene.from === 0 ? 1 : ss(t, scene.from, scene.from + .67))
                * (1 - ss(t, scene.to - .72, scene.to));
            fr.g({ a: alpha }, () => scene.draw(fr, t));
        }
        if (t >= 9 && t <= 15.1) title(fr, t, false);
        if (t >= 138) title(fr, t, true);
        if (new URLSearchParams(location.search).get('subs') !== 'off') {
            VKit.subs(fr, t, subtitles, 'night');
        }
        for (const [time, sound] of audio) fr.sfx(time, sound, { gain: .54 });
    }
});
})();
