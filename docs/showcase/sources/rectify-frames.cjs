#!/usr/bin/env node
/*
 * rectify-frames.cjs - make the device photographs in docs/showcase/frames
 * axis-aligned and clean, and keep the screen opening consistent with them.
 *
 * Usage (from the repository root):
 *   node docs/showcase/sources/rectify-frames.cjs [--frames DIR] [--out DIR] [--report] [id ...]
 *
 *   id         titan-2 | titan-2-elite | pixel-7a (default: every *-geometry.json in --frames)
 *   --frames   input frames directory (default: docs/showcase/frames)
 *   --out      output directory (default: same as --frames, i.e. overwrite in place).
 *              Use a new directory first to review the result, then copy it over.
 *   --report   print straightness, tilt, corner and fringe metrics for the input frames; writes nothing.
 *
 * Pipeline per device:
 *   1. Halo removal: warm, bright pixels (background or reflection residue) that are connected to
 *      the outside of the body, within a 40 px band of the silhouette, are made transparent.
 *      Dark hardware, neutral rims and key legends are not touched.
 *   2. Edge measurement: subpixel 50 % crossings per row/column on the four outer edges, robust
 *      least-squares lines (rounded corners and protrusions such as side buttons are rejected).
 *   3. Rectification: the four line intersections are mapped onto an axis-aligned rectangle with
 *      the same mean position and size, using a projective transform (4-point DLT).
 *   4. Resampling: frame, outer mask and screen mask use the same transform (bicubic, premultiplied
 *      alpha). Alpha values below 4/255 are cleared and values above 250/255 are set to opaque.
 *   5. Screen opening: its four edges are fitted after the transform, the corners are snapped to an
 *      integer axis-aligned rectangle and geometry.json is updated (bounds, corners, camera, radii,
 *      rectification record).
 *
 * The script is deterministic and reads no network resources. Source PNGs are not modified unless
 * --out points to the same directory.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const sharp = require('sharp');

const DEFAULT_FRAMES = path.join(__dirname, '..', 'frames');
const DEVICE_IDS = ['titan-2', 'titan-2-elite', 'pixel-7a'];

const HALO = { band: 40, minLuma: 100, minWarmth: 6, partialLuma: 200, neutralLuma: 200, passes: 3 }; // luma = max(R,G,B); warmth = R - B
const LINE_REJECT_PX = 1.0; // inlier threshold for the rectification fit
const METRIC_REJECT_PX = 3.0; // inlier threshold used when reporting straightness metrics
const TRIM = 0.12; // ignore 12 % of the edge extent at each end (rounded corners)
const ALPHA_FLOOR = 4; // alpha < 4 becomes 0
const ALPHA_CEIL = 250; // alpha > 250 becomes 255
const EDGE_GAIN = 2; // silhouette alpha contrast around 0.5: narrows the soft ramp to about 1-2 px

// ---------------------------------------------------------------- image I/O

async function readRGBA(file) {
  const { data, info } = await sharp(file).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  if (info.channels !== 4) throw new Error(`Expected RGBA data: ${file}`);
  return { w: info.width, h: info.height, px: new Uint8Array(data.buffer, data.byteOffset, data.length) };
}

async function writeRGBA(file, img) {
  await sharp(Buffer.from(img.px), { raw: { width: img.w, height: img.h, channels: 4 } })
    .png({ compressionLevel: 9 })
    .toFile(file);
}

function alphaOf(img) {
  const A = new Float32Array(img.w * img.h);
  for (let i = 0; i < A.length; i++) A[i] = img.px[i * 4 + 3] / 255;
  return A;
}

// ---------------------------------------------------------------- edge measurement

// Linear crossing of threshold `thr` between an inside sample (ci, ai) and an outside sample (co, ao).
function crossing(ci, ai, co, ao, thr) {
  return ci + ((ai - thr) / (ai - ao)) * (co - ci);
}

// Subpixel boundary points of the thresholded region on the four sides.
// left/right: {t: y, p: x}; top/bottom: {t: x, p: y}.
function scanEdges(A, w, h, thr = 0.5) {
  const out = { left: [], right: [], top: [], bottom: [] };
  for (let y = 0; y < h; y++) {
    const r = y * w;
    let x1 = -1;
    for (let x = 0; x < w; x++) if (A[r + x] >= thr) { x1 = x; break; }
    if (x1 >= 0) out.left.push({ t: y + 0.5, p: x1 === 0 ? 0 : crossing(x1 + 0.5, A[r + x1], x1 - 0.5, A[r + x1 - 1], thr) });
    let x2 = -1;
    for (let x = w - 1; x >= 0; x--) if (A[r + x] >= thr) { x2 = x; break; }
    if (x2 >= 0) out.right.push({ t: y + 0.5, p: x2 === w - 1 ? w : crossing(x2 + 0.5, A[r + x2], x2 + 1.5, A[r + x2 + 1], thr) });
  }
  for (let x = 0; x < w; x++) {
    let y1 = -1;
    for (let y = 0; y < h; y++) if (A[y * w + x] >= thr) { y1 = y; break; }
    if (y1 >= 0) out.top.push({ t: x + 0.5, p: y1 === 0 ? 0 : crossing(y1 + 0.5, A[y1 * w + x], y1 - 0.5, A[(y1 - 1) * w + x], thr) });
    let y2 = -1;
    for (let y = h - 1; y >= 0; y--) if (A[y * w + x] >= thr) { y2 = y; break; }
    if (y2 >= 0) out.bottom.push({ t: x + 0.5, p: y2 === h - 1 ? h : crossing(y2 + 0.5, A[y2 * w + x], y2 + 1.5, A[(y2 + 1) * w + x], thr) });
  }
  return out;
}

function leastSquares(pts) {
  let n = 0, St = 0, Stt = 0, Sp = 0, Stp = 0;
  for (const q of pts) { n++; St += q.t; Stt += q.t * q.t; Sp += q.p; Stp += q.t * q.p; }
  const den = n * Stt - St * St;
  const b = den === 0 ? 0 : (n * Stp - St * Sp) / den;
  const a = (Sp - b * St) / n;
  return { a, b };
}

// Robust line p = a + b*t. Points in the outer TRIM fraction of the extent are ignored; points farther
// than `rejectPx` from the running fit are rejected iteratively.
function fitLine(points, rejectPx) {
  if (points.length < 10) throw new Error(`Too few edge points (${points.length})`);
  let tmin = Infinity, tmax = -Infinity;
  for (const q of points) { if (q.t < tmin) tmin = q.t; if (q.t > tmax) tmax = q.t; }
  const span = tmax - tmin;
  const band = points.filter(q => q.t >= tmin + TRIM * span && q.t <= tmax - TRIM * span);
  let inl = band;
  let line = leastSquares(inl);
  for (let it = 0; it < 10; it++) {
    const next = band.filter(q => Math.abs(q.p - (line.a + line.b * q.t)) <= rejectPx);
    line = leastSquares(next);
    if (next.length === inl.length) { inl = next; break; }
    inl = next;
  }
  let maxDev = 0, sumSq = 0, s0 = Infinity, s1 = -Infinity;
  for (const q of inl) {
    const r = Math.abs(q.p - (line.a + line.b * q.t));
    if (r > maxDev) maxDev = r;
    sumSq += r * r;
    if (q.t < s0) s0 = q.t;
    if (q.t > s1) s1 = q.t;
  }
  return {
    a: line.a,
    b: line.b,
    n: inl.length,
    rejected: band.length - inl.length,
    maxDev,
    rms: Math.sqrt(sumSq / inl.length),
    tStart: s0,
    tEnd: s1,
    tilt: Math.abs(line.b) * (s1 - s0),
  };
}

// Intersection of a vertical-like line x = a + b*y with a horizontal-like line y = c + d*x.
function intersect(V, Hz) {
  const x = (V.a + V.b * Hz.a) / (1 - V.b * Hz.b);
  return { x, y: Hz.a + Hz.b * x };
}

// Fits the four sides of a rectangle-like region given its subpixel boundary points.
function fitSides(edges, rejectPx) {
  const left = fitLine(edges.left, rejectPx);
  const right = fitLine(edges.right, rejectPx);
  const top = fitLine(edges.top, rejectPx);
  const bottom = fitLine(edges.bottom, rejectPx);
  return {
    left, right, top, bottom,
    corners: {
      TL: intersect(left, top),
      TR: intersect(right, top),
      BR: intersect(right, bottom),
      BL: intersect(left, bottom),
    },
  };
}

// Interior corner angles in degrees (90 is exact). Left/right lines are x = a + b*y,
// top/bottom lines are y = c + d*x, so their directions are (b, 1) and (1, d).
function cornerAngles(sides) {
  const angle = (u, v) => {
    const c = (u[0] * v[0] + u[1] * v[1]) / (Math.hypot(u[0], u[1]) * Math.hypot(v[0], v[1]));
    return Math.acos(Math.max(-1, Math.min(1, c))) * 180 / Math.PI;
  };
  const L = sides.left.b, R = sides.right.b, T = sides.top.b, B = sides.bottom.b;
  return {
    TL: angle([L, 1], [1, T]), // down the left edge, right along the top edge
    TR: angle([R, 1], [-1, -T]), // down the right edge, left along the top edge
    BR: angle([-R, -1], [-1, -B]), // up the right edge, left along the bottom edge
    BL: angle([-L, -1], [1, B]), // up the left edge, right along the bottom edge
  };
}

// ---------------------------------------------------------------- projective transform

function solveLinear(M, v) {
  const n = v.length;
  const A = M.map((row, i) => [...row, v[i]]);
  for (let c = 0; c < n; c++) {
    let p = c;
    for (let r = c + 1; r < n; r++) if (Math.abs(A[r][c]) > Math.abs(A[p][c])) p = r;
    [A[c], A[p]] = [A[p], A[c]];
    for (let r = 0; r < n; r++) {
      if (r === c) continue;
      const f = A[r][c] / A[c][c];
      for (let k = c; k <= n; k++) A[r][k] -= f * A[c][k];
    }
  }
  return A.map((row, i) => row[n] / row[i]);
}

// Homography h (8 values, h8 = 1) with to = H(from) for four point pairs.
function homography(from, to) {
  const M = [], v = [];
  for (let i = 0; i < 4; i++) {
    const [x, y] = from[i], [X, Y] = to[i];
    M.push([x, y, 1, 0, 0, 0, -X * x, -X * y]); v.push(X);
    M.push([0, 0, 0, x, y, 1, -Y * x, -Y * y]); v.push(Y);
  }
  return solveLinear(M, v);
}

function applyH(h, x, y) {
  const w = h[6] * x + h[7] * y + 1;
  return [(h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w];
}

// ---------------------------------------------------------------- halo removal

// 8-connected BFS distance from a seed mask (Uint8Array of 0/1). Returns Int32Array, -1 if unreachable.
function distanceFrom(seed, w, h, maxD = Infinity) {
  const D = new Int32Array(w * h).fill(-1);
  const q = new Int32Array(w * h);
  let qh = 0, qt = 0;
  for (let i = 0; i < seed.length; i++) if (seed[i]) { D[i] = 0; q[qt++] = i; }
  while (qh < qt) {
    const i = q[qh++];
    const d = D[i];
    if (d >= maxD) continue;
    const x = i % w, y = (i - x) / w;
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      if (!dx && !dy) continue;
      const nx = x + dx, ny = y + dy;
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
      const j = ny * w + nx;
      if (D[j] === -1) { D[j] = d + 1; q[qt++] = j; }
    }
  }
  return D;
}

// Makes warm, bright residue that is connected to the outside of the body transparent.
function removeHalo(A, frame, w, h) {
  const N = w * h;
  const exterior = new Uint8Array(N);
  for (let i = 0; i < N; i++) exterior[i] = A[i] < 0.5 ? 1 : 0;
  const D = distanceFrom(exterior, w, h, HALO.band);
  const cand = new Uint8Array(N);
  for (let i = 0; i < N; i++) {
    if (A[i] < 0.5 || D[i] < 1 || D[i] > HALO.band) continue;
    if (frame.px[i * 4 + 3] < 128) continue; // screen opening is not part of the body edge
    const r = frame.px[i * 4], g = frame.px[i * 4 + 1], b = frame.px[i * 4 + 2];
    const luma = Math.max(r, g, b);
    if (luma > HALO.minLuma && r - b > HALO.minWarmth) cand[i] = 1;
  }
  // Flood from candidates touching the exterior through candidates only.
  const removed = new Uint8Array(N);
  const q = new Int32Array(N);
  let qh = 0, qt = 0;
  for (let i = 0; i < N; i++) {
    if (!cand[i] || D[i] !== 1) continue;
    removed[i] = 1; q[qt++] = i;
  }
  while (qh < qt) {
    const i = q[qh++];
    const x = i % w, y = (i - x) / w;
    const nb = [[x + 1, y], [x - 1, y], [x, y + 1], [x, y - 1]];
    for (const [nx, ny] of nb) {
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
      const j = ny * w + nx;
      if (cand[j] && !removed[j]) { removed[j] = 1; q[qt++] = j; }
    }
  }
  const out = Float32Array.from(A);
  let count = 0;
  for (let i = 0; i < N; i++) if (removed[i]) { out[i] = 0; count++; }
  // Bright residue on the outer ring: partial-alpha pixels above partialLuma, and neutral pixels above
  // neutralLuma (background-coloured halo). Cool metallic rims (Pixel 7a) stay intact.
  for (let pass = 0; pass < HALO.passes; pass++) {
    const ext = new Uint8Array(N);
    for (let i = 0; i < N; i++) ext[i] = out[i] < 0.5 ? 1 : 0;
    const Dr = distanceFrom(ext, w, h, 1);
    let changed = 0;
    for (let i = 0; i < N; i++) {
      if (Dr[i] !== 1 || out[i] < 0.05) continue;
      if (frame.px[i * 4 + 3] < 128) continue;
      const r = frame.px[i * 4], g = frame.px[i * 4 + 1], b = frame.px[i * 4 + 2];
      const luma = Math.max(r, g, b);
      const neutral = Math.abs(r - b) < 12;
      const partial = out[i] < 0.95;
      if ((partial && luma > HALO.partialLuma) || (neutral && luma > HALO.neutralLuma)) { out[i] = 0; changed++; }
    }
    count += changed;
    if (!changed) break;
  }
  return { A: out, removed: count };
}

// ---------------------------------------------------------------- resampling

function catmullRom(t) {
  const t2 = t * t, t3 = t2 * t;
  return [
    (-t3 + 2 * t2 - t) / 2,
    (3 * t3 - 5 * t2 + 2) / 2,
    (-3 * t3 + 4 * t2 + t) / 2,
    (t3 - t2) / 2,
  ];
}

// Silhouette alpha after resampling: same contrast curve as the source, and tails below 12 % are
// cleared so that isolated specks next to the arcs do not survive as a grey fringe.
function sharpenAlpha(a255) {
  let v = 0.5 + (a255 / 255 - 0.5) * EDGE_GAIN;
  if (v < 0.12) return 0;
  if (v > 0.98) v = 1;
  return Math.round(Math.min(1, v) * 255);
}

// Warps an RGBA image with the inverse map hInv (destination -> source).
// Colour: bicubic on premultiplied values, divided by the bicubic alpha weight (no dark or light fringes).
// Alpha: bilinear, so the anti-aliased silhouette spreads at most 1 px beyond the source edge
// (a bicubic alpha would leave a 2-3 px grey tail with negative lobes).
function warp(src, hInv, outW, outH) {
  const { w, h } = src;
  const P = new Float32Array(w * h * 4);
  for (let i = 0; i < w * h; i++) {
    const a = src.px[i * 4 + 3] / 255;
    P[i * 4] = src.px[i * 4] * a;
    P[i * 4 + 1] = src.px[i * 4 + 1] * a;
    P[i * 4 + 2] = src.px[i * 4 + 2] * a;
    P[i * 4 + 3] = a;
  }
  const out = new Uint8Array(outW * outH * 4);
  for (let j = 0; j < outH; j++) {
    for (let i = 0; i < outW; i++) {
      const [sx, sy] = applyH(hInv, i + 0.5, j + 0.5);
      const ix = sx - 0.5, iy = sy - 0.5;
      const x0 = Math.floor(ix), y0 = Math.floor(iy);
      if (x0 < -2 || y0 < -2 || x0 > w + 1 || y0 > h + 1) continue;
      // Bilinear alpha.
      const fx = ix - x0, fy = iy - y0;
      let AL = 0;
      for (let ky = 0; ky < 2; ky++) {
        const yy = Math.min(h - 1, Math.max(0, y0 + ky));
        for (let kx = 0; kx < 2; kx++) {
          const xx = Math.min(w - 1, Math.max(0, x0 + kx));
          AL += P[(yy * w + xx) * 4 + 3] * (kx ? fx : 1 - fx) * (ky ? fy : 1 - fy);
        }
      }
      const alpha = Math.min(1, Math.max(0, AL)) * 255;
      if (alpha < ALPHA_FLOOR) continue;
      // Bicubic premultiplied colour and bicubic alpha weight.
      const wx = catmullRom(fx), wy = catmullRom(fy);
      let R = 0, G = 0, B = 0, BA = 0;
      for (let ky = 0; ky < 4; ky++) {
        const yy = Math.min(h - 1, Math.max(0, y0 - 1 + ky));
        for (let kx = 0; kx < 4; kx++) {
          const xx = Math.min(w - 1, Math.max(0, x0 - 1 + kx));
          const wgt = wx[kx] * wy[ky];
          const k = (yy * w + xx) * 4;
          R += P[k] * wgt; G += P[k + 1] * wgt; B += P[k + 2] * wgt; BA += P[k + 3] * wgt;
        }
      }
      const s = BA > 1e-4 ? 1 / BA : 0;
      const o = (j * outW + i) * 4;
      out[o] = Math.min(255, Math.max(0, Math.round(R * s)));
      out[o + 1] = Math.min(255, Math.max(0, Math.round(G * s)));
      out[o + 2] = Math.min(255, Math.max(0, Math.round(B * s)));
      out[o + 3] = alpha > ALPHA_CEIL ? 255 : Math.round(alpha);
    }
  }
  return { w: outW, h: outH, px: out };
}

// Fills the colour of the screen opening (and any unknown pixel) from the nearest bezel pixel,
// so that bezel colour extends into the opening when the opening is cut differently.
// Returns RGB triplets (Uint8Array of w*h*3).
function extendColorIntoOpening(px, Ac, As, w, h) {
  const N = w * h;
  const rgb = new Uint8Array(N * 3);
  const known = new Uint8Array(N);
  const q = new Int32Array(N);
  let qh = 0, qt = 0;
  for (let i = 0; i < N; i++) {
    rgb[i * 3] = px[i * 4]; rgb[i * 3 + 1] = px[i * 4 + 1]; rgb[i * 3 + 2] = px[i * 4 + 2];
    if (Ac[i] >= 0.5 && As[i] < 0.5) { known[i] = 1; q[qt++] = i; }
  }
  while (qh < qt) {
    const i = q[qh++];
    const x = i % w, y = (i - x) / w;
    const nb = [[x + 1, y], [x - 1, y], [x, y + 1], [x, y - 1]];
    for (const [nx, ny] of nb) {
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
      const j = ny * w + nx;
      if (known[j] || Ac[j] < 0.5) continue;
      known[j] = 1;
      rgb[j * 3] = rgb[i * 3]; rgb[j * 3 + 1] = rgb[i * 3 + 1]; rgb[j * 3 + 2] = rgb[i * 3 + 2];
      q[qt++] = j;
    }
  }
  return rgb;
}

// ---------------------------------------------------------------- per-device processing

function targetRectangle(c) {
  const xL = (c.TL.x + c.BL.x) / 2, xR = (c.TR.x + c.BR.x) / 2;
  const yT = (c.TL.y + c.TR.y) / 2, yB = (c.BL.y + c.BR.y) / 2;
  return { TL: { x: xL, y: yT }, TR: { x: xR, y: yT }, BR: { x: xR, y: yB }, BL: { x: xL, y: yB } };
}

const ORDER = ['TL', 'TR', 'BR', 'BL'];

function cornerList(c) { return ORDER.map(k => [c[k].x, c[k].y]); }

// Boundary of a binary region, as a Uint8Array of pixels inside that touch the opposite class.
function boundaryOf(mask, w, h) {
  const B = new Uint8Array(w * h);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = y * w + x;
    const m = mask[i];
    for (let dy = -1; dy <= 1 && !B[i]; dy++) for (let dx = -1; dx <= 1; dx++) {
      const nx = x + dx, ny = y + dy;
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
      if (mask[ny * w + nx] !== m) { B[i] = 1; break; }
    }
  }
  return B;
}

async function loadDevice(dir, id) {
  const geometry = JSON.parse(fs.readFileSync(path.join(dir, `${id}-geometry.json`), 'utf8'));
  const frame = await readRGBA(path.join(dir, `${id}-frame.png`));
  const outer = await readRGBA(path.join(dir, `${id}-outer-mask.png`));
  const screen = await readRGBA(path.join(dir, `${id}-screen-mask.png`));
  if (outer.w !== frame.w || outer.h !== frame.h || screen.w !== frame.w || screen.h !== frame.h) {
    throw new Error(`${id}: frame and mask sizes differ`);
  }
  return { geometry, frame, outer, screen };
}

// Metrics for the report: straightness, tilt, corner angles, screen fit and fringe counts.
function measure(dev) {
  const { frame, outer, screen, geometry } = dev;
  const w = frame.w, h = frame.h;
  const Ao = alphaOf(outer), As = alphaOf(screen);
  const sides = fitSides(scanEdges(Ao, w, h), METRIC_REJECT_PX);
  const sc = fitSides(scanEdges(As, w, h), METRIC_REJECT_PX);
  const bounds = geometry.screen.bounds;
  const geomEdges = {
    left: bounds.x,
    right: bounds.x + bounds.width,
    top: bounds.y,
    bottom: bounds.y + bounds.height,
  };
  const screenMismatch = {
    left: Math.abs(sc.left.a + sc.left.b * ((sc.left.tStart + sc.left.tEnd) / 2) - geomEdges.left),
    right: Math.abs(sc.right.a + sc.right.b * ((sc.right.tStart + sc.right.tEnd) / 2) - geomEdges.right),
    top: Math.abs(sc.top.a + sc.top.b * ((sc.top.tStart + sc.top.tEnd) / 2) - geomEdges.top),
    bottom: Math.abs(sc.bottom.a + sc.bottom.b * ((sc.bottom.tStart + sc.bottom.tEnd) / 2) - geomEdges.bottom),
  };
  // Fringe: partial frame alpha far from the outer contour and far from the screen opening.
  const bodyMask = new Uint8Array(w * h), holeMask = new Uint8Array(w * h), partial = new Uint8Array(w * h);
  for (let i = 0; i < w * h; i++) {
    bodyMask[i] = Ao[i] >= 0.5 ? 1 : 0;
    holeMask[i] = As[i] >= 0.5 ? 1 : 0;
    const fa = frame.px[i * 4 + 3];
    partial[i] = fa > 0 && fa < 255 ? 1 : 0;
  }
  const dContour = distanceFrom(boundaryOf(bodyMask, w, h), w, h, 3);
  const dHole = distanceFrom(boundaryOf(holeMask, w, h), w, h, 3);
  let partialAll = 0, partialOutside = 0, partialHoleEdge = 0;
  for (let i = 0; i < w * h; i++) {
    if (!partial[i]) continue;
    const far = dContour[i] === -1 || dContour[i] > 2;
    const nearHole = dHole[i] !== -1 && dHole[i] <= 2;
    if (far) partialAll++;
    if (far && !nearHole) partialOutside++;
    if (nearHole && far) partialHoleEdge++;
  }
  const angles = cornerAngles(sides);
  return { sides, screen: sc, screenMismatch, angles, partialAll, partialOutside, partialHoleEdge };
}

function fmt(v, d = 2) { return Number(v).toFixed(d); }

function printReport(id, label, m) {
  console.log(`\n[${id}] ${label}`);
  for (const e of ['left', 'right', 'top', 'bottom']) {
    const l = m.sides[e];
    console.log(`  ${e.padEnd(7)} n=${String(l.n).padStart(5)} rejected=${String(l.rejected).padStart(4)} maxDev=${fmt(l.maxDev)} px rms=${fmt(l.rms)} px tilt=${fmt(l.tilt)} px`);
  }
  console.log(`  corner angles: TL ${fmt(m.angles.TL, 3)} TR ${fmt(m.angles.TR, 3)} BR ${fmt(m.angles.BR, 3)} BL ${fmt(m.angles.BL, 3)} deg`);
  for (const e of ['left', 'right', 'top', 'bottom']) {
    const l = m.screen[e];
    console.log(`  screen ${e.padEnd(6)} maxDev=${fmt(l.maxDev)} px tilt=${fmt(l.tilt)} px geometry-offset=${fmt(m.screenMismatch[e])} px`);
  }
  console.log(`  partial alpha outside 2px outer band: ${m.partialAll} (screen-opening AA excluded: ${m.partialOutside})`);
}

async function processDevice(dir, outDir, id) {
  const dev = await loadDevice(dir, id);
  const { geometry, frame, outer, screen } = dev;
  const w = frame.w, h = frame.h;

  // 1. Halo removal on the outer silhouette.
  const Ao = alphaOf(outer);
  const halo = removeHalo(Ao, frame, w, h);
  const Ac = halo.A;
  // Soft ramps and low-alpha tails of the source silhouette would survive the warp as a 2-3 px grey fringe.
  for (let i = 0; i < Ac.length; i++) Ac[i] = Math.min(1, Math.max(0, 0.5 + (Ac[i] - 0.5) * EDGE_GAIN));

  // 2. Outer edges and projective rectangle.
  const outerSides = fitSides(scanEdges(Ac, w, h), LINE_REJECT_PX);
  const srcCorners = outerSides.corners;
  const dstCorners = targetRectangle(srcCorners);
  const srcList = cornerList(srcCorners), dstList = cornerList(dstCorners);
  const hDS = homography(dstList, srcList); // destination -> source (inverse map)
  const hSD = homography(srcList, dstList); // source -> destination

  // 3. Screen opening measured in source, mapped to destination, fitted to a rectangle.
  const As = alphaOf(screen);
  const scSrc = scanEdges(As, w, h);
  const scSrcSides = fitSides(scSrc, LINE_REJECT_PX);
  const mapEdges = (pts, vertical) => pts.map(q => {
    const [X, Y] = vertical ? applyH(hSD, q.p, q.t) : applyH(hSD, q.t, q.p);
    return vertical ? { t: Y, p: X } : { t: X, p: Y };
  });
  const scDst = {
    left: mapEdges(scSrc.left, true),
    right: mapEdges(scSrc.right, true),
    top: mapEdges(scSrc.top, false),
    bottom: mapEdges(scSrc.bottom, false),
  };
  // The opening keeps the axis-aligned rectangle recorded in geometry.json (measured display corners
  // that the screenshot mapping and the LED checks rely on). Its measured edges after the outer
  // transform are reported as a consistency check (screenTiltPx).
  const screenSidesDst = fitSides(scDst, LINE_REJECT_PX);
  const gc = geometry.screen.corners;
  const sx0 = gc[0][0], sy0 = gc[0][1], sx1 = gc[2][0], sy1 = gc[2][1];
  const screenDriftPx = Math.max(
    Math.abs(sx0 - (screenSidesDst.left.a + screenSidesDst.left.b * ((screenSidesDst.left.tStart + screenSidesDst.left.tEnd) / 2))),
    Math.abs(sx1 - (screenSidesDst.right.a + screenSidesDst.right.b * ((screenSidesDst.right.tStart + screenSidesDst.right.tEnd) / 2))),
    Math.abs(sy0 - (screenSidesDst.top.a + screenSidesDst.top.b * ((screenSidesDst.top.tStart + screenSidesDst.top.tEnd) / 2))),
    Math.abs(sy1 - (screenSidesDst.bottom.a + screenSidesDst.bottom.b * ((screenSidesDst.bottom.tStart + screenSidesDst.bottom.tEnd) / 2))),
  );

  // 4. Screen opening: its own transform (source rectangle -> integer destination rectangle).
  // The bezel keeps the outer-silhouette transform; the opening is cut out afterwards.
  const srcScreenList = cornerList(scSrcSides.corners);
  const dstScreenList = [[sx0, sy0], [sx1, sy0], [sx1, sy1], [sx0, sy1]];
  const hScreenDS = homography(dstScreenList, srcScreenList); // destination -> source

  // 5. Resample. The hole is filled with the nearest bezel colour so that the bezel can
  // extend into the opening area when the opening moves; the opening is then cut with its own mask.
  const filled = extendColorIntoOpening(frame.px, Ac, As, w, h);
  const cleanFrame = { w, h, px: new Uint8Array(w * h * 4) };
  const cleanOuter = { w, h, px: new Uint8Array(w * h * 4) };
  const cleanScreen = { w, h, px: new Uint8Array(w * h * 4) };
  // The camera notch is removed from the opening mask here and cut again below at the
  // camera's position after the bezel transform, so the dot and the notch stay together.
  const cam0 = geometry.screen.camera;
  for (let i = 0; i < w * h; i++) {
    cleanFrame.px[i * 4] = filled[i * 3];
    cleanFrame.px[i * 4 + 1] = filled[i * 3 + 1];
    cleanFrame.px[i * 4 + 2] = filled[i * 3 + 2];
    cleanFrame.px[i * 4 + 3] = Math.round(Ac[i] * 255);
    cleanScreen.px[i * 4] = screen.px[i * 4];
    cleanScreen.px[i * 4 + 1] = screen.px[i * 4 + 1];
    cleanScreen.px[i * 4 + 2] = screen.px[i * 4 + 2];
    cleanScreen.px[i * 4 + 3] = screen.px[i * 4 + 3];
  }
  if (cam0) {
    // The source notch has isolated 0 pixels up to about 3 px outside its nominal radius.
    const r0 = cam0[2] + 3;
    for (let y = Math.max(0, Math.floor(cam0[1] - r0)); y <= Math.min(h - 1, Math.ceil(cam0[1] + r0)); y++) {
      for (let x = Math.max(0, Math.floor(cam0[0] - r0)); x <= Math.min(w - 1, Math.ceil(cam0[0] + r0)); x++) {
        if (Math.hypot(x + 0.5 - cam0[0], y + 0.5 - cam0[1]) <= r0) cleanScreen.px[(y * w + x) * 4 + 3] = 255;
      }
    }
  }
  const outW = geometry.width, outH = geometry.height;
  const warped = warp(cleanFrame, hDS, outW, outH);
  const newScreen = warp(cleanScreen, hScreenDS, outW, outH);
  if (cam0) {
    const [ccx, ccy] = applyH(hSD, cam0[0], cam0[1]);
    const rNew = cam0[2] * ((sx1 - sx0) / geometry.screen.bounds.width + (sy1 - sy0) / geometry.screen.bounds.height) / 2;
    for (let y = Math.max(0, Math.floor(ccy - rNew - 2)); y <= Math.min(outH - 1, Math.ceil(ccy + rNew + 2)); y++) {
      for (let x = Math.max(0, Math.floor(ccx - rNew - 2)); x <= Math.min(outW - 1, Math.ceil(ccx + rNew + 2)); x++) {
        const d = Math.hypot(x + 0.5 - ccx, y + 0.5 - ccy);
        const cov = Math.min(1, Math.max(0, rNew - d + 0.5));
        const o = (y * outW + x) * 4 + 3;
        newScreen.px[o] = Math.round(newScreen.px[o] * (1 - cov));
      }
    }
  }
  // Outer silhouette: alpha of the warped body (opening included), black RGB like the source mask.
  const newOuter = { w: outW, h: outH, px: new Uint8Array(warped.px) };
  for (let i = 0; i < outW * outH; i++) {
    newOuter.px[i * 4] = newOuter.px[i * 4 + 1] = newOuter.px[i * 4 + 2] = 0;
    newOuter.px[i * 4 + 3] = sharpenAlpha(warped.px[i * 4 + 3]);
  }
  // Frame: body alpha minus the opening alpha.
  const newFrame = { w: outW, h: outH, px: new Uint8Array(warped.px) };
  for (let i = 0; i < outW * outH; i++) {
    const a = (newOuter.px[i * 4 + 3] / 255) * (1 - newScreen.px[i * 4 + 3] / 255) * 255;
    const q = a < ALPHA_FLOOR ? 0 : a > ALPHA_CEIL ? 255 : Math.round(a);
    newFrame.px[i * 4 + 3] = q;
    if (q === 0) newFrame.px[i * 4] = newFrame.px[i * 4 + 1] = newFrame.px[i * 4 + 2] = 0;
  }

  // 5. Geometry update.
  const oldB = geometry.screen.bounds;
  const sc = { w: (sx1 - sx0) / oldB.width, h: (sy1 - sy0) / oldB.height };
  const s = (sc.w + sc.h) / 2;
  // The camera is photographic content in the frame, so it follows the bezel transform.
  // cameraDriftPx: distance between that position and the opening transform at the camera centre.
  const hScreenSD = homography(srcScreenList, dstScreenList);
  let cameraDriftPx = 0;
  if (geometry.screen.camera) {
    const [ax, ay] = applyH(hSD, geometry.screen.camera[0], geometry.screen.camera[1]);
    const [bx, by] = applyH(hScreenSD, geometry.screen.camera[0], geometry.screen.camera[1]);
    cameraDriftPx = Math.hypot(ax - bx, ay - by);
  }
  const cam = geometry.screen.camera ? (() => {
    const [cx, cy] = applyH(hSD, geometry.screen.camera[0], geometry.screen.camera[1]);
    return [Math.round(cx * 10) / 10, Math.round(cy * 10) / 10, Math.round(geometry.screen.camera[2] * s * 10) / 10];
  })() : null;

  const newGeometry = JSON.parse(JSON.stringify(geometry));
  newGeometry.screen.bounds = { x: sx0, y: sy0, width: sx1 - sx0, height: sy1 - sy0 };
  newGeometry.screen.corners = [[sx0, sy0], [sx1, sy0], [sx1, sy1], [sx0, sy1]];
  if (cam) newGeometry.screen.camera = cam;
  newGeometry.screen.radius = Math.round(geometry.screen.radius * s * 10) / 10;
  newGeometry.screen.lower_radius = Math.round(geometry.screen.lower_radius * s * 10) / 10;
  newGeometry.outer_mask = `${id}-outer-mask.png`;
  newGeometry.rectification = {
    source_corners: ORDER.map(k => [Math.round(srcCorners[k].x * 10) / 10, Math.round(srcCorners[k].y * 10) / 10]),
    destination_corners: ORDER.map(k => [Math.round(dstCorners[k].x * 10) / 10, Math.round(dstCorners[k].y * 10) / 10]),
    method: 'Straight-edge line fit of the halo-cleaned outer silhouette, projective transform onto an axis-aligned rectangle (bicubic, premultiplied alpha); same transform for the frame, outer mask and screen opening; screen corners fitted after the transform and snapped to integers. Generated by sources/rectify-frames.cjs.',
  };

  fs.mkdirSync(outDir, { recursive: true });
  await writeRGBA(path.join(outDir, `${id}-frame.png`), newFrame);
  await writeRGBA(path.join(outDir, `${id}-outer-mask.png`), newOuter);
  await writeRGBA(path.join(outDir, `${id}-screen-mask.png`), newScreen);
  fs.writeFileSync(path.join(outDir, `${id}-geometry.json`), JSON.stringify(newGeometry, null, 2) + '\n');
  return { halo: halo.removed, srcCorners, dstCorners, screenRect: [sx0, sy0, sx1, sy1], screenDriftPx, cameraDriftPx };
}

// ---------------------------------------------------------------- CLI

function parseArgs(argv) {
  const opts = { frames: DEFAULT_FRAMES, out: null, report: false, ids: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--frames') opts.frames = path.resolve(argv[++i]);
    else if (a === '--out') opts.out = path.resolve(argv[++i]);
    else if (a === '--report') opts.report = true;
    else if (a === '--help' || a === '-h') opts.help = true;
    else opts.ids.push(a);
  }
  if (!opts.out) opts.out = opts.frames;
  if (!opts.ids.length) opts.ids = DEVICE_IDS.filter(id => fs.existsSync(path.join(opts.frames, `${id}-geometry.json`)));
  return opts;
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    console.log('Usage: node docs/showcase/sources/rectify-frames.cjs [--frames DIR] [--out DIR] [--report] [id ...]');
    return;
  }
  for (const id of opts.ids) {
    if (!DEVICE_IDS.includes(id)) throw new Error(`Unknown device id: ${id}`);
    if (opts.report) {
      const dev = await loadDevice(opts.frames, id);
      printReport(id, opts.frames, measure(dev));
      continue;
    }
    const result = await processDevice(opts.frames, opts.out, id);
    console.log(`[${id}] halo pixels removed: ${result.halo}`);
    console.log(`  source corners: ${ORDER.map(k => `${fmt(result.srcCorners[k].x, 1)},${fmt(result.srcCorners[k].y, 1)}`).join(' ')}`);
    console.log(`  target corners: ${ORDER.map(k => `${fmt(result.dstCorners[k].x, 1)},${fmt(result.dstCorners[k].y, 1)}`).join(' ')}`);
    console.log(`  screen rectangle: ${result.screenRect.join(', ')} (bezel-transform drift at opening edges: ${fmt(result.screenDriftPx, 1)} px; camera drift: ${fmt(result.cameraDriftPx, 1)} px)`);
    console.log(`  written to ${opts.out}`);
  }
}

if (require.main === module) {
  main().catch(e => {
    console.error(e.stack || e.message);
    process.exit(1);
  });
}

module.exports = { scanEdges, fitLine, fitSides, homography, applyH, removeHalo, warp, measure, loadDevice, distanceFrom, boundaryOf };
