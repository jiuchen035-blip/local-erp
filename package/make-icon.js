// 生成 package/LocalERP.ico（蓝底白色立方体），纯 Node 无依赖，PNG-in-ICO 格式
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const S = 256; // 画布边长
const px = Buffer.alloc(S * S * 4, 0); // RGBA

function put(x, y, r, g, b, a) {
  const i = (y * S + x) * 4;
  px[i] = r; px[i + 1] = g; px[i + 2] = b; px[i + 3] = a;
}

// 背景圆角矩形（竖向渐变 靛蓝 -> 深蓝），带 1px 抗锯齿边
const R = 52;
for (let y = 0; y < S; y++) {
  for (let x = 0; x < S; x++) {
    const t = y / S;
    const r = Math.round(37 + (30 - 37) * t);
    const g = Math.round(99 + (64 - 99) * t);
    const b = Math.round(235 + (175 - 235) * t);
    // 到圆角矩形的符号距离（近似）
    const cx = Math.max(R - x, 0, x - (S - 1 - R));
    const cy = Math.max(R - y, 0, y - (S - 1 - R));
    const d = Math.sqrt(cx * cx + cy * cy) - R;
    let a = 255;
    if (d > 1) continue;
    if (d > -1) a = Math.round(255 * (1 - (d + 1) / 2)); // 边缘羽化
    put(x, y, r, g, b, a);
  }
}

// 点是否在多边形内（射线法）
function inPoly(poly, x, y) {
  let inside = false;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const [xi, yi] = poly[i], [xj, yj] = poly[j];
    if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

// 等距立方体（三面三色）
const cx = 128, cy = 132, w = 74, h = 42, depth = 78;
const top    = [[cx, cy - depth], [cx + w, cy - depth + h], [cx, cy - depth + 2 * h], [cx - w, cy - depth + h]];
const left   = [[cx - w, cy - depth + h], [cx, cy - depth + 2 * h], [cx, cy + 2 * h], [cx - w, cy + h]];
const right  = [[cx, cy - depth + 2 * h], [cx + w, cy - depth + h], [cx + w, cy + h], [cx, cy + 2 * h]];
for (let y = 0; y < S; y++) {
  for (let x = 0; x < S; x++) {
    let c = null;
    if (inPoly(top, x, y)) c = [255, 255, 255];
    else if (inPoly(left, x, y)) c = [219, 231, 255];
    else if (inPoly(right, x, y)) c = [158, 190, 250];
    if (c) put(x, y, c[0], c[1], c[2], 255);
  }
}

// ---- 编码 PNG（RGBA，filter 0）----
function crc32(buf) {
  let table = crc32.table;
  if (!table) {
    table = crc32.table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
      table[n] = c;
    }
  }
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = table[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(S, 0); ihdr.writeUInt32BE(S, 4);
ihdr[8] = 8; ihdr[9] = 6; // 8bit RGBA
const raw = Buffer.alloc(S * (S * 4 + 1));
for (let y = 0; y < S; y++) {
  raw[y * (S * 4 + 1)] = 0;
  px.copy(raw, y * (S * 4 + 1) + 1, y * S * 4, (y + 1) * S * 4);
}
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
  chunk('IHDR', ihdr),
  chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
  chunk('IEND', Buffer.alloc(0)),
]);

// ---- 包成 ICO（256px，PNG 压缩条目）----
const head = Buffer.alloc(6); head.writeUInt16LE(0); head.writeUInt16LE(1); head.writeUInt16LE(1);
const entry = Buffer.alloc(16);
entry[0] = 0; entry[1] = 0;              // 256px
entry.writeUInt16LE(1, 4); entry.writeUInt16LE(32, 6); // planes, bpp
entry.writeUInt32LE(png.length, 8); entry.writeUInt32LE(22, 12);
fs.writeFileSync(path.join(__dirname, 'LocalERP.ico'), Buffer.concat([head, entry, png]));
console.log('LocalERP.ico written,', png.length + 22, 'bytes');
