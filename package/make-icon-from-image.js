// 从用户提供的 PNG 生成应用图标 package/app-icon.ico
// 步骤：解码 PNG → 镜像补丁盖掉左上角水印 → 裁掉白边 → 多尺寸缩放+圆角透明
//       → 传统 BMP 条目 ICO（16/24/32/48/64/128/256 全套，jpackage/资源编译器兼容性最好）
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const SRC = process.argv[2];
const OUT_ICO = path.join(__dirname, 'app-icon.ico');
const SIZES = [16, 24, 32, 48, 64, 128, 256];

// ---------- PNG 解码（bit depth 8，color type 2/6，非隔行） ----------
function decodePNG(buf) {
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error('not a PNG');
  let pos = 8, W = 0, H = 0, depth = 0, ctype = 0, interlace = 0;
  const idats = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') {
      W = data.readUInt32BE(0); H = data.readUInt32BE(4);
      depth = data[8]; ctype = data[9]; interlace = data[12];
    } else if (type === 'IDAT') idats.push(data);
    else if (type === 'IEND') break;
    pos += 12 + len;
  }
  if (depth !== 8 || (ctype !== 2 && ctype !== 6) || interlace !== 0)
    throw new Error(`unsupported PNG: depth=${depth} ctype=${ctype} interlace=${interlace}`);
  const ch = ctype === 6 ? 4 : 3;
  const raw = zlib.inflateSync(Buffer.concat(idats));
  const stride = W * ch;
  const out = Buffer.alloc(W * H * ch);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < H; y++) {
    const f = raw[y * (stride + 1)];
    const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    const cur = Buffer.alloc(stride);
    for (let i = 0; i < stride; i++) {
      const a = i >= ch ? cur[i - ch] : 0;
      const b = prev[i];
      const c = i >= ch ? prev[i - ch] : 0;
      let v = line[i];
      if (f === 1) v += a;
      else if (f === 2) v += b;
      else if (f === 3) v += (a + b) >> 1;
      else if (f === 4) {
        const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
      }
      cur[i] = v & 0xff;
    }
    cur.copy(out, y * stride);
    prev = cur;
  }
  return { W, H, ch, data: out };
}

// ---------- 主流程 ----------
const img = decodePNG(fs.readFileSync(SRC));
const { W, H, ch, data } = img;
const px = (x, y) => {
  const i = (y * W + x) * ch;
  return [data[i], data[i + 1], data[i + 2]];
};

// 1) 左上角水印用右上角镜像覆盖（圆角对称，镜像后无痕）
const PW = Math.min(280, W >> 1), PH = Math.min(140, H >> 2);
for (let y = 0; y < PH; y++) {
  for (let x = 0; x < PW; x++) {
    const src = ((y * W) + (W - 1 - x)) * ch;
    const dst = ((y * W) + x) * ch;
    for (let k = 0; k < ch; k++) data[dst + k] = data[src + k];
  }
}

// 2) 找非白像素包围盒（裁掉白底），取中央正方形
let x0 = W, x1 = 0, y0 = H, y1 = 0;
for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
  const [r, g, b] = px(x, y);
  if (!(r > 242 && g > 242 && b > 242)) {
    if (x < x0) x0 = x; if (x > x1) x1 = x;
    if (y < y0) y0 = y; if (y > y1) y1 = y;
  }
}
x0 += 2; y0 += 2; x1 -= 2; y1 -= 2;
const cw = x1 - x0 + 1, chh = y1 - y0 + 1;
const side = Math.min(cw, chh);
const cx = x0 + (cw - side >> 1), cy = y0 + (chh - side >> 1);
console.log(`src ${W}x${H}, crop square ${side}px at (${cx},${cy})`);

// 缩放 + 圆角透明 → 返回 RGBA buffer
function render(S) {
  const out = Buffer.alloc(S * S * 4);
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
    const fx = cx + (x + 0.5) * side / S - 0.5, fy = cy + (y + 0.5) * side / S - 0.5;
    const ix = Math.max(0, Math.min(W - 2, Math.floor(fx))), iy = Math.max(0, Math.min(H - 2, Math.floor(fy)));
    const dx = fx - ix, dy = fy - iy;
    const i = (y * S + x) * 4;
    for (let k = 0; k < 3; k++) {
      const p00 = data[(iy * W + ix) * ch + k],     p10 = data[(iy * W + ix + 1) * ch + k];
      const p01 = data[((iy + 1) * W + ix) * ch + k], p11 = data[((iy + 1) * W + ix + 1) * ch + k];
      out[i + k] = Math.round(p00 * (1 - dx) * (1 - dy) + p10 * dx * (1 - dy) + p01 * (1 - dx) * dy + p11 * dx * dy);
    }
  }
  const RAD = S * 0.19, feather = Math.max(1, S / 96), inset = Math.max(0.75, S / 256);
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
    const nx = Math.max(RAD - x, 0, x - (S - 1 - RAD));
    const ny = Math.max(RAD - y, 0, y - (S - 1 - RAD));
    const d = Math.sqrt(nx * nx + ny * ny) - RAD + inset;
    let a = d >= feather ? 0 : d <= -feather ? 255 : Math.round(255 * (1 - (d + feather) / (2 * feather)));
    out[(y * S + x) * 4 + 3] = a;
  }
  return out;
}

// ---------- 传统 BMP 条目编码（32bit BGRA 自底向上 + 全 0 AND 掩码） ----------
function bmpEntry(rgba, S) {
  const andStride = Math.ceil(S / 8 / 4) * 4; // AND 掩码每行 4 字节对齐
  const body = Buffer.alloc(40 + S * S * 4 + andStride * S);
  body.writeUInt32LE(40, 0);            // BITMAPINFOHEADER.biSize
  body.writeInt32LE(S, 4);              // 宽
  body.writeInt32LE(S * 2, 8);          // 高 = XOR + AND 两倍
  body.writeUInt16LE(1, 12);            // planes
  body.writeUInt16LE(32, 14);           // bpp
  body.writeUInt32LE(S * S * 4 + andStride * S, 20); // biSizeImage
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const si = (y * S + x) * 4, di = 40 + ((S - 1 - y) * S + x) * 4; // 自底向上，RGBA→BGRA
      body[di] = rgba[si + 2]; body[di + 1] = rgba[si + 1];
      body[di + 2] = rgba[si]; body[di + 3] = rgba[si + 3];
    }
  }
  return body;
}

// ---------- 组装 ICO ----------
const images = SIZES.map(S => ({ S, data: bmpEntry(render(S), S) }));
const dir = Buffer.alloc(6);
dir.writeUInt16LE(0, 0); dir.writeUInt16LE(1, 2); dir.writeUInt16LE(images.length, 4);
const entries = [];
let offset = 6 + 16 * images.length;
for (const { S, data: d } of images) {
  const e = Buffer.alloc(16);
  e[0] = S % 256; e[1] = S % 256;        // 256 记为 0
  e.writeUInt16LE(1, 4); e.writeUInt16LE(32, 6);
  e.writeUInt32LE(d.length, 8); e.writeUInt32LE(offset, 12);
  entries.push(e);
  offset += d.length;
}
fs.writeFileSync(OUT_ICO, Buffer.concat([dir, ...entries, ...images.map(i => i.data)]));

// 预览图（256 那层转 PNG 供人工检查）
const prev = render(256);
const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(256, 0); ihdr.writeUInt32BE(256, 4); ihdr[8] = 8; ihdr[9] = 6;
function crc32(buf) {
  if (!crc32.table) {
    crc32.table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
      crc32.table[n] = c;
    }
  }
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = crc32.table[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}
function chunk(type, d) {
  const len = Buffer.alloc(4); len.writeUInt32BE(d.length);
  const td = Buffer.concat([Buffer.from(type), d]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
const raw = Buffer.alloc(256 * (256 * 4 + 1));
for (let y = 0; y < 256; y++) {
  raw[y * (256 * 4 + 1)] = 0;
  prev.copy(raw, y * (256 * 4 + 1) + 1, y * 256 * 4, (y + 1) * 256 * 4);
}
fs.writeFileSync(path.join(__dirname, 'icon-preview.png'), Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
  chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0)),
]));
console.log('app-icon.ico written,', SIZES.join('/'), 'sizes,', offset, 'bytes total');
