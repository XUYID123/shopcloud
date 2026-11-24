// All props are drawn for this film in Canvas coordinates.
window.Props = (() => {
'use strict';
const ink = '#202d34', paper = '#e5dcc6', cyan = '#81d3dd', amber = '#ffc97f', red = '#f18b8b';
const muted = '#9bb5c4';
const money = fen => (fen / 100).toFixed(2);
const example = { unitPrice: 3000, bags: 2, grams: 5000 };
example.goods = example.unitPrice * example.bags;
example.freight = 1000 + Math.ceil(Math.max(0, example.grams * example.bags - 20000) / 10000) * 500;
example.payable = example.goods + example.freight;

function label(fr, value, x, y, size = 32, color = paper, align = 'c', font = 'sansm', track = 0) {
    fr.text(value, x, y, { size, color, align, font, track });
}
function heading(fr, zh, en) {
    label(fr, zh, 115, 101, 46, paper, 'l', 'serifm', 3);
    label(fr, en, 118, 151, 22, cyan, 'l', 'mono', 3);
    fr.line(115, 176, 206, 176, { color: '#496977', w: 2 });
}
function annotation(fr, text, color = muted) {
    label(fr, text, 960, 786, 27, color, 'c', 'mono', 1);
}
function floor(fr, x, y, rx, ry = 25) {
    fr.ellipse(x, y, rx, ry, { fill: fr.rgrad(x, y, 0, rx, [[0, '#020609', .48], [1, '#020609', 0]]) });
}
function flour(fr, x, y, scale = 1, batch = '') {
    fr.g({ at: [x, y], s: scale }, () => {
        const fabric = fr.grad(-86, -100, 91, 161, [[0, '#f3e4bd'], [.35, '#e5d6ad'], [.77, '#b9a17b'], [1, '#866d53']]);
        floor(fr, 0, 174, 114, 14);
        fr.path('M-75 -146 Q-38 -139 0 -146 Q38 -152 74 -143 L66 -130 Q71 -85 86 -24 Q109 78 91 149 Q86 167 59 173 L-59 173 Q-90 172 -94 150 Q-101 90 -88 18 L-69 -103 Z',
            { fill: fabric, color: '#f0d7a7', w: 2 });
        fr.path('M-75 -146 Q-20 -155 20 -147 Q49 -151 74 -143 L68 -124 Q-4 -117 -69 -127 Z',
            { fill: '#b6a585', color: '#76654e', w: 1.6 });
        fr.line(-62, -133, 61, -133, { color: '#6f5d46', w: 2, dash: [5, 4] });
        fr.path('M-86 88 Q-26 104 92 90 L94 127 Q-36 148 -92 129 Z', { fill: '#183c4b' });
        fr.path('M-75 -104 Q-61 -69 -73 -30 M65 -116 Q55 -69 71 -14 M-85 143 Q-61 153 -38 154 M60 143 Q80 132 87 102',
            { color: '#866d53', w: 2, a: .6 });
        fr.path('M-2 33 Q-12 -6 8 -58 M0 17 Q-24 10 -23 -8 Q-8 -8 0 17 M3 4 Q22 -3 23 -20 Q8 -20 3 4 M5 -13 Q-18 -22 -15 -37 Q2 -36 5 -13 M7 -26 Q27 -34 25 -48 Q11 -50 7 -26 M8 -39 Q-3 -52 11 -67 Q19 -51 8 -39',
            { color: '#645235', w: 2.6, fill: '#a98952' });
        label(fr, '小麦粉', 0, 62, 36, '#342e25', 'c', 'serifh', 2);
        label(fr, '净重 5 kg', 0, 112, 23, '#f5e4bd');
        if (batch) label(fr, batch, 0, 153, 20, '#433526', 'c', 'mono');
    });
}
function zigzag(width, y) {
    let path = '';
    for (let x = width / 2; x >= -width / 2; x -= 14) {
        path += ' L' + x + ' ' + (y + ((Math.round((width / 2 - x) / 14) % 2) * 8));
    }
    return path;
}
function receipt(fr, title, lines, status = '', tint = amber) {
    const w = 336, middle = w / 2;
    floor(fr, 0, 206, 221, 22);
    fr.path('M-' + middle + ' -210 H' + middle + ' V196' + zigzag(w, 196) + ' Z', {
        fill: fr.grad(-middle, -211, middle, 211, [[0, '#f3ead5'], [.73, '#e0d4bb'], [1, '#bdaf97']]),
        color: '#a99b87', w: 2
    });
    label(fr, title, 0, -151, 36, ink, 'c', 'serifh', 3);
    fr.line(-139, -112, 139, -112, { color: '#7a7466', w: 2, dash: [6, 7] });
    lines.forEach(([key, value], index) => {
        const y = -72 + index * 45;
        label(fr, key, -137, y, 24, '#46545a', 'l');
        label(fr, value, 137, y, 25, ink, 'r', 'sans');
    });
    fr.line(-139, 112, 139, 112, { color: '#7a7466', w: 2, dash: [6, 7] });
    for (let i = 0; i < 32; i += 1) {
        fr.rect(-133 + i * 3, 135, i % 4 === 2 ? 2 : 1, 32, { fill: '#4b514e' });
    }
    if (status) stamp(fr, status, 65, 150, tint, 1, true);
}
function stamp(fr, value, x, y, tint, progress = 1, onPaper = false) {
    const palette = onPaper ? (tint === cyan ? '#205164' : tint === red ? '#a33e40' : '#724814') : tint;
    const w = Math.max(132, fr.measure(value, { font: 'mono', size: 27 }) + 32);
    const opacity = Math.min(1, progress * 2.3);
    if (!opacity) return;
    fr.g({ at: [x, y - (1 - progress) * 42], rot: -.07, a: opacity, s: 1 + (1 - progress) * .1 }, () => {
        fr.rect(-w / 2, -25, w, 50, { r: 5, color: palette, w: 3, fill: onPaper ? '#e8ddc5' : '#152332' });
        label(fr, value, 0, 0, 27, palette, 'c', 'mono');
    });
}
function phone(fr, title, lines, button, tint = cyan) {
    fr.rect(-174, -275, 348, 550, { r: 43, color: '#526f81', w: 4, fill: '#101820' });
    fr.rect(-154, -256, 308, 512, { r: 31, color: '#1d3949', w: 2, fill: '#192835' });
    fr.rect(-41, -240, 82, 10, { r: 5, fill: '#080f17' });
    label(fr, title, 0, -170, 40, paper, 'c', 'serifm');
    fr.line(-125, -127, 125, -127, { color: '#436070', w: 2 });
    lines.forEach(([key, value], index) => {
        const y = -78 + index * (lines.length > 3 ? 51 : 67);
        label(fr, key, -120, y, 23, '#a7bdcc', 'l');
        label(fr, value, 120, y, 27, paper, 'r', 'sans');
    });
    fr.rect(-129, 128, 258, 61, { r: 8, fill: '#243c48', color: tint, w: 1.5 });
    label(fr, button, 0, 159, 25, tint, 'c', 'sans');
    fr.rect(-50, 227, 100, 4, { r: 2, fill: '#587286' });
}
function shelf(fr, x, y, w) {
    fr.g({ at: [x, y] }, () => {
        floor(fr, 0, 76, w * .6, 21);
        const edge = '#526674';
        fr.poly([[-w / 2, -1], [-w / 2 + 38, -48], [w / 2 - 29, -48], [w / 2, -1]], { fill: '#253e4b', color: edge, w: 2 });
        fr.rect(-w / 2, 0, w, 22, { fill: '#334957', color: edge, w: 2 });
        fr.rect(-w / 2 + 15, 22, 14, 51, { fill: '#253540', color: edge, w: 1 });
        fr.rect(w / 2 - 29, 22, 14, 51, { fill: '#253540', color: edge, w: 1 });
    });
}
function envelope(fr, x, y, text, tint = cyan, scale = 1) {
    fr.g({ at: [x, y], s: scale, rot: -.05 }, () => {
        floor(fr, 0, 88, 160, 18);
        fr.rect(-146, -82, 292, 164, { r: 7, fill: '#203a4d', color: tint, w: 2 });
        fr.path('M-145 -80 L0 26 L145 -80 M-145 80 L-37 0 M145 80 L37 0', { color: tint, w: 2, a: .45 });
        fr.rect(-95, -20, 190, 42, { r: 4, fill: '#162a37', color: tint, w: 1 });
        label(fr, text, 0, 1, 26, tint, 'c', 'sans');
    });
}
function parcel(fr, x, y, s = 1, marked = false) {
    fr.g({ at: [x, y], s }, () => {
        floor(fr, 0, 131, 225, 24);
        fr.poly([[-201, -68], [-105, -143], [157, -143], [201, -70], [201, 99], [132, 135], [-201, 135], [-201, -68]],
            { fill: '#9f794a', color: '#d0a16a', w: 2 });
        fr.poly([[-201, -68], [132, -68], [201, -70], [132, 3], [-201, 3], [-201, -68]], { fill: '#b88f5d', color: '#d0a16a', w: 2 });
        fr.poly([[132, 3], [201, -70], [201, 99], [132, 135], [132, 3]], { fill: '#745638', color: '#ac8153', w: 2 });
        fr.path('M-51 -143 L-128 -68 L-128 135 L-85 135 L-85 -68 L-8 -143 Z', { fill: '#e0bd85', a: .62 });
        if (marked) {
            fr.rect(-57, 34, 151, 78, { r: 3, fill: '#eee4ce' });
            label(fr, '运单', 20, 51, 20, ink, 'c', 'sans');
            for (let i = 0; i < 31; i += 1) fr.rect(-37 + i * 3.5, 72, i % 3 ? 1.5 : 3, 25, { fill: ink });
        }
    });
}
return { ink, paper, cyan, amber, red, muted, money, example, label, heading, annotation, floor, flour,
    receipt, stamp, phone, shelf, envelope, parcel };
})();
