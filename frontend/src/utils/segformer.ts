import type { BoundingBox } from '@/types';

/**
 * Self-hosted browser-side SegFormer-B2-Clothes (18-class apparel semantic segmentation).
 * Model is served from our own origin (/models/segformer_b2_clothes/model_quantized.onnx, ~27.5MB q8)
 * and ONNX Runtime WASM from /ort/ so Chinese users never stall on HuggingFace / jsDelivr CDNs.
 *
 * Upgraded with:
 * - Softmax continuous probability map (smooth anti-aliased edges instead of hard 128x128 binary masks)
 * - 4-connected component denoising (strips phone screenshot UI buttons / icons / speckles)
 * - Standalone SegFormer-First multi-garment auto-discovery (top/dress/pants/skirt/shoes/bag/hat/scarf)
 * - Real pixel dominant color extraction from masked garment alpha
 *
 * 18 classes from mattmdjaga/segformer_b2_clothes:
 *  0 Background   1 Hat        2 Hair        3 Sunglasses
 *  4 Upper-clothes 5 Skirt     6 Pants       7 Dress
 *  8 Belt         9 Left-shoe 10 Right-shoe 11 Face
 * 12 Left-leg    13 Right-leg 14 Left-arm   15 Right-arm
 * 16 Bag         17 Scarf
 */
export const SEGFORMER_MODEL_URL = '/models/segformer_b2_clothes/model_quantized.onnx';
const INPUT_SIZE = 512;
const IMAGENET_MEAN = [0.485, 0.456, 0.406] as const;
const IMAGENET_STD = [0.229, 0.224, 0.225] as const;

export const SEG_LABELS = {
  BACKGROUND: 0,
  HAT: 1,
  HAIR: 2,
  SUNGLASSES: 3,
  UPPER_CLOTHES: 4,
  SKIRT: 5,
  PANTS: 6,
  DRESS: 7,
  BELT: 8,
  LEFT_SHOE: 9,
  RIGHT_SHOE: 10,
  FACE: 11,
  LEFT_LEG: 12,
  RIGHT_LEG: 13,
  LEFT_ARM: 14,
  RIGHT_ARM: 15,
  BAG: 16,
  SCARF: 17,
} as const;

const HUMAN_BODY_CLASSES = new Set<number>([
  SEG_LABELS.HAIR,
  SEG_LABELS.FACE,
  SEG_LABELS.LEFT_LEG,
  SEG_LABELS.RIGHT_LEG,
  SEG_LABELS.LEFT_ARM,
  SEG_LABELS.RIGHT_ARM,
]);

const ALL_GARMENT_CLASSES = new Set<number>([
  SEG_LABELS.HAT,
  SEG_LABELS.SUNGLASSES,
  SEG_LABELS.UPPER_CLOTHES,
  SEG_LABELS.SKIRT,
  SEG_LABELS.PANTS,
  SEG_LABELS.DRESS,
  SEG_LABELS.BELT,
  SEG_LABELS.LEFT_SHOE,
  SEG_LABELS.RIGHT_SHOE,
  SEG_LABELS.BAG,
  SEG_LABELS.SCARF,
]);

export interface ParsedClothesMap {
  image: HTMLImageElement;
  origW: number;
  origH: number;
  mapW: number;
  mapH: number;
  numClasses: number;
  classMap: Uint8Array;
  /** Quantized softmax probability [c * planeSize + p] in 0..255 for smooth edge upsampling */
  classProbs: Uint8Array;
  classCounts: Int32Array;
  hasHumanWearing: boolean;
  garmentRatio: number;
  objectUrl: string;
}

export interface SemanticCutoutResult {
  cutoutFile: File;
  cutoutDataUrl: string;
  cropFile: File;
  cropDataUrl: string;
  matchedClasses: number[];
  coverage: number;
  bbox: BoundingBox;
  dominantColor: { color: string; colorHex: string };
}

export interface DiscoveredGarmentSpec {
  id: string;
  category: 'outerwear' | 'top' | 'dress' | 'bottom' | 'shoes' | 'bag' | 'accessory';
  baseName: string;
  svgType: string;
  classes: number[];
  maxComponents: number;
  bbox: BoundingBox;
  pixelRatio: number;
  defaultSeasons: string[];
}

export interface DiscoveredGarmentResult {
  spec: DiscoveredGarmentSpec;
  name: string;
  category: 'outerwear' | 'top' | 'dress' | 'bottom' | 'shoes' | 'bag' | 'accessory';
  color: string;
  colorHex: string;
  seasons: string[];
  styleTags: string[];
  svgType: string;
  bbox: BoundingBox;
  cutoutFile: File;
  cutoutDataUrl: string;
  cropFile: File;
  cropDataUrl: string;
}

type OrtModule = typeof import('onnxruntime-web');
type OrtSession = Awaited<ReturnType<OrtModule['InferenceSession']['create']>>;

let sessionPromise: Promise<{ ort: OrtModule; session: OrtSession }> | null = null;

async function getSegFormerSession(): Promise<{ ort: OrtModule; session: OrtSession }> {
  if (!sessionPromise) {
    sessionPromise = (async () => {
      const ort = await import('onnxruntime-web');
      ort.env.wasm.wasmPaths = '/ort/';
      ort.env.wasm.numThreads = 1; // Safe without COOP/COEP SharedArrayBuffer headers
      ort.env.logLevel = 'error';

      let session: OrtSession;
      try {
        session = await ort.InferenceSession.create(SEGFORMER_MODEL_URL, {
          executionProviders: ['wasm'],
          graphOptimizationLevel: 'all',
        });
      } catch (err) {
        sessionPromise = null;
        throw err;
      }
      return { ort, session };
    })();
  }
  return sessionPromise;
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('图片解码失败'));
    img.src = src;
  });
}

function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = reject;
    reader.readAsDataURL(blob);
  });
}

function canvasToBlob(canvas: HTMLCanvasElement, mime: string, quality?: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('Canvas 导出失败'))), mime, quality);
  });
}

/**
 * Runs SegFormer-B2-Clothes once on the full source photo and returns a reusable 18-class semantic map
 * with per-class softmax probabilities for smooth edge alpha reconstruction.
 * Call `disposeParsedClothesMap(parsed)` when finished to release the object URL.
 */
export async function parseClothesSemanticMap(file: File, timeoutMs = 45000): Promise<ParsedClothesMap> {
  const objectUrl = URL.createObjectURL(file);
  try {
    const runTask = (async (): Promise<ParsedClothesMap> => {
      const [img, { ort, session }] = await Promise.all([loadImage(objectUrl), getSegFormerSession()]);
      const origW = img.naturalWidth;
      const origH = img.naturalHeight;

      const prepCanvas = document.createElement('canvas');
      prepCanvas.width = INPUT_SIZE;
      prepCanvas.height = INPUT_SIZE;
      const pctx = prepCanvas.getContext('2d', { willReadFrequently: true });
      if (!pctx) throw new Error('Canvas 不可用');
      pctx.drawImage(img, 0, 0, INPUT_SIZE, INPUT_SIZE);
      const { data } = pctx.getImageData(0, 0, INPUT_SIZE, INPUT_SIZE);

      const area = INPUT_SIZE * INPUT_SIZE;
      const floatData = new Float32Array(3 * area);
      for (let i = 0; i < area; i++) {
        const idx = i * 4;
        floatData[i] = (data[idx] / 255 - IMAGENET_MEAN[0]) / IMAGENET_STD[0];
        floatData[area + i] = (data[idx + 1] / 255 - IMAGENET_MEAN[1]) / IMAGENET_STD[1];
        floatData[2 * area + i] = (data[idx + 2] / 255 - IMAGENET_MEAN[2]) / IMAGENET_STD[2];
      }

      const inputName = session.inputNames[0] || 'pixel_values';
      const inputTensor = new ort.Tensor('float32', floatData, [1, 3, INPUT_SIZE, INPUT_SIZE]);
      const outputs = await session.run({ [inputName]: inputTensor });
      const outputName = session.outputNames[0] || 'logits';
      const logits = outputs[outputName];
      const dims = logits.dims; // [1, 18, outH, outW]
      const numClasses = Number(dims[1] || 18);
      const mapH = Number(dims[2] || 128);
      const mapW = Number(dims[3] || 128);
      const outData = logits.data as Float32Array;
      const planeSize = mapW * mapH;

      const classMap = new Uint8Array(planeSize);
      const classProbs = new Uint8Array(numClasses * planeSize);
      const classCounts = new Int32Array(numClasses);
      const expBuf = new Float32Array(numClasses);

      for (let p = 0; p < planeSize; p++) {
        let bestC = 0;
        let maxVal = outData[p];
        for (let c = 1; c < numClasses; c++) {
          const v = outData[c * planeSize + p];
          if (v > maxVal) {
            maxVal = v;
            bestC = c;
          }
        }
        classMap[p] = bestC;
        classCounts[bestC] += 1;

        let sumExp = 0;
        for (let c = 0; c < numClasses; c++) {
          const e = Math.exp(outData[c * planeSize + p] - maxVal);
          expBuf[c] = e;
          sumExp += e;
        }
        const inv = sumExp > 0 ? 255 / sumExp : 0;
        for (let c = 0; c < numClasses; c++) {
          classProbs[c * planeSize + p] = Math.min(255, Math.round(expBuf[c] * inv));
        }
      }

      let humanPixels = 0;
      let garmentPixels = 0;
      let distinctGarmentClasses = 0;
      for (let c = 1; c < numClasses; c++) {
        if (HUMAN_BODY_CLASSES.has(c)) humanPixels += classCounts[c];
        if (ALL_GARMENT_CLASSES.has(c)) {
          garmentPixels += classCounts[c];
          if (classCounts[c] > planeSize * 0.01) distinctGarmentClasses += 1;
        }
      }

      const hasHumanWearing = humanPixels > planeSize * 0.006 || distinctGarmentClasses >= 2;
      const garmentRatio = garmentPixels / planeSize;

      return {
        image: img,
        origW,
        origH,
        mapW,
        mapH,
        numClasses,
        classMap,
        classProbs,
        classCounts,
        hasHumanWearing,
        garmentRatio,
        objectUrl,
      };
    })();

    const timeoutPromise = new Promise<never>((_, reject) =>
      setTimeout(() => reject(new Error('服装语义分割超时')), timeoutMs)
    );

    return await Promise.race([runTask, timeoutPromise]);
  } catch (err) {
    URL.revokeObjectURL(objectUrl);
    throw err;
  }
}

export function disposeParsedClothesMap(parsed: ParsedClothesMap | null | undefined) {
  if (parsed?.objectUrl) {
    URL.revokeObjectURL(parsed.objectUrl);
  }
}

/**
 * 4-connected component filter on a binary mask at semantic map resolution (128x128).
 * Strips stray phone screenshot UI icon speckles while keeping up to `maxComponents` main garment regions.
 */
function denoiseBinaryMask(
  mask: Uint8Array,
  w: number,
  h: number,
  maxComponents = 2,
  minPixels = 12
): { cleaned: Uint8Array; totalPixels: number; minX: number; minY: number; maxX: number; maxY: number } {
  const size = w * h;
  const visited = new Uint8Array(size);
  const queue = new Int32Array(size);
  const components: Array<{ pixels: number[]; count: number }> = [];

  for (let i = 0; i < size; i++) {
    if (!mask[i] || visited[i]) continue;
    let head = 0;
    let tail = 0;
    queue[tail++] = i;
    visited[i] = 1;
    const pixels: number[] = [];

    while (head < tail) {
      const idx = queue[head++];
      pixels.push(idx);
      const x = idx % w;
      const y = (idx - x) / w;

      if (x > 0) {
        const n = idx - 1;
        if (mask[n] && !visited[n]) {
          visited[n] = 1;
          queue[tail++] = n;
        }
      }
      if (x + 1 < w) {
        const n = idx + 1;
        if (mask[n] && !visited[n]) {
          visited[n] = 1;
          queue[tail++] = n;
        }
      }
      if (y > 0) {
        const n = idx - w;
        if (mask[n] && !visited[n]) {
          visited[n] = 1;
          queue[tail++] = n;
        }
      }
      if (y + 1 < h) {
        const n = idx + w;
        if (mask[n] && !visited[n]) {
          visited[n] = 1;
          queue[tail++] = n;
        }
      }
    }
    components.push({ pixels, count: pixels.length });
  }

  const cleaned = new Uint8Array(size);
  if (!components.length) {
    return { cleaned, totalPixels: 0, minX: w, minY: h, maxX: -1, maxY: -1 };
  }

  components.sort((a, b) => b.count - a.count);
  const largest = components[0].count;
  const minKeep = Math.max(minPixels, Math.round(largest * 0.08));

  let totalPixels = 0;
  let minX = w;
  let minY = h;
  let maxX = -1;
  let maxY = -1;

  for (let ci = 0; ci < Math.min(maxComponents, components.length); ci++) {
    const comp = components[ci];
    if (ci > 0 && comp.count < minKeep) break;
    if (comp.count < minPixels) break;
    for (const idx of comp.pixels) {
      cleaned[idx] = 1;
      totalPixels += 1;
      const x = idx % w;
      const y = (idx - x) / w;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
  }

  return { cleaned, totalPixels, minX, minY, maxX, maxY };
}

/**
 * Extracts a realistic Chinese fashion color name + hex directly from opaque garment pixels.
 */
function extractDominantColorFromCanvas(canvas: HTMLCanvasElement): { color: string; colorHex: string } {
  const sampleW = 64;
  const sampleH = 64;
  const sc = document.createElement('canvas');
  sc.width = sampleW;
  sc.height = sampleH;
  const sctx = sc.getContext('2d', { willReadFrequently: true });
  if (!sctx) return { color: '米白', colorHex: '#F3EDE2' };

  sctx.drawImage(canvas, 0, 0, sampleW, sampleH);
  const { data } = sctx.getImageData(0, 0, sampleW, sampleH);

  let sumR = 0;
  let sumG = 0;
  let sumB = 0;
  let count = 0;

  for (let i = 0; i < data.length; i += 4) {
    const a = data[i + 3];
    if (a < 210) continue;
    sumR += data[i];
    sumG += data[i + 1];
    sumB += data[i + 2];
    count += 1;
  }

  if (count === 0) return { color: '米白', colorHex: '#F3EDE2' };

  const r = Math.round(sumR / count);
  const g = Math.round(sumG / count);
  const b = Math.round(sumB / count);
  const hex = `#${[r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('').toUpperCase()}`;

  // Convert RGB to HSL for reliable fashion color naming
  const rf = r / 255;
  const gf = g / 255;
  const bf = b / 255;
  const max = Math.max(rf, gf, bf);
  const min = Math.min(rf, gf, bf);
  const l = (max + min) / 2;
  const d = max - min;
  const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
  let h = 0;
  if (d !== 0) {
    if (max === rf) h = ((gf - bf) / d + (gf < bf ? 6 : 0)) * 60;
    else if (max === gf) h = ((bf - rf) / d + 2) * 60;
    else h = ((rf - gf) / d + 4) * 60;
  }

  if (l < 0.22) return { color: '炭黑', colorHex: '#262628' };
  if (s < 0.14) {
    if (l > 0.87) return { color: '纯白', colorHex: '#FFFFFF' };
    if (l > 0.74) return { color: '米白', colorHex: '#F3EDE2' };
    if (l > 0.52) return { color: '燕麦', colorHex: '#DFD3C3' };
    if (l > 0.34) return { color: '深灰', colorHex: hex };
    return { color: '炭黑', colorHex: '#262628' };
  }

  if (h >= 20 && h <= 52) {
    if (l > 0.76) return { color: '米白', colorHex: '#F3EDE2' };
    if (l > 0.62) return { color: '燕麦', colorHex: '#DFD3C3' };
    if (l > 0.45) return { color: '卡其', colorHex: '#C7B299' };
    return { color: '焦糖棕', colorHex: '#8A5230' };
  }
  if (h > 52 && h <= 78) {
    return l > 0.65 ? { color: '米白', colorHex: '#F3EDE2' } : { color: '卡其', colorHex: '#C7B299' };
  }
  if (h > 78 && h <= 165) {
    return { color: '橄榄绿', colorHex: hex };
  }
  if (h > 165 && h <= 255) {
    if (l < 0.46) return { color: '牛仔蓝', colorHex: '#3B5372' };
    return { color: '雾霾蓝', colorHex: '#6F869C' };
  }
  if (h > 315 || h < 20) {
    if (l > 0.72) return { color: '淡粉', colorHex: hex };
    if (l < 0.42) return { color: '酒红', colorHex: hex };
    return { color: '砖红', colorHex: hex };
  }
  return { color: '燕麦', colorHex: hex };
}

/**
 * Maps an item's category and Chinese name to preferred SegFormer garment class groups.
 * Groups keep logical pairs together (e.g. Left-shoe + Right-shoe).
 */
function candidateClassGroups(category: string, name = ''): number[][] {
  const n = name.toLowerCase();
  switch (category) {
    case 'outerwear':
    case 'top':
      if (/连衣裙|吊带裙|长裙|背带裙/.test(n)) {
        return [[SEG_LABELS.DRESS], [SEG_LABELS.UPPER_CLOTHES, SEG_LABELS.SKIRT]];
      }
      return [[SEG_LABELS.UPPER_CLOTHES], [SEG_LABELS.DRESS]];
    case 'dress':
      return [[SEG_LABELS.DRESS], [SEG_LABELS.UPPER_CLOTHES, SEG_LABELS.SKIRT]];
    case 'bottom':
      if (/裙/.test(n)) return [[SEG_LABELS.SKIRT], [SEG_LABELS.DRESS], [SEG_LABELS.PANTS]];
      return [[SEG_LABELS.PANTS], [SEG_LABELS.SKIRT]];
    case 'shoes':
      return [[SEG_LABELS.LEFT_SHOE, SEG_LABELS.RIGHT_SHOE]];
    case 'bag':
      return [[SEG_LABELS.BAG]];
    case 'accessory':
      if (/帽/.test(n)) return [[SEG_LABELS.HAT]];
      if (/墨镜|太阳镜|眼镜/.test(n)) return [[SEG_LABELS.SUNGLASSES]];
      if (/腰带|皮带/.test(n)) return [[SEG_LABELS.BELT]];
      if (/围巾|丝巾|披肩/.test(n)) return [[SEG_LABELS.SCARF], [SEG_LABELS.UPPER_CLOTHES]];
      return [[SEG_LABELS.SCARF], [SEG_LABELS.HAT], [SEG_LABELS.SUNGLASSES], [SEG_LABELS.BELT], [SEG_LABELS.BAG]];
    default:
      return [[SEG_LABELS.UPPER_CLOTHES], [SEG_LABELS.DRESS], [SEG_LABELS.PANTS], [SEG_LABELS.SKIRT]];
  }
}

/**
 * Core smooth-alpha garment extraction from a known class group & optional region box.
 * Uses connected-component denoising + continuous softmax probability reconstruction for crisp edges.
 */
async function extractClassGroupMask(
  parsed: ParsedClothesMap,
  keepClasses: number[],
  options: {
    bbox?: BoundingBox | null;
    maxComponents?: number;
    maxEdge?: number;
    filePrefix?: string;
    minPixels?: number;
  } = {}
): Promise<SemanticCutoutResult | null> {
  const { bbox = null, maxComponents = 2, maxEdge = 1024, filePrefix = 'seg', minPixels = 12 } = options;
  const { mapW, mapH, classMap, classProbs, origW, origH, image } = parsed;
  const planeSize = mapW * mapH;
  const keepSet = new Set(keepClasses);

  const expand = 0.14;
  const rx0 = bbox ? Math.max(0, Math.floor((bbox.x - bbox.w * expand) * mapW)) : 0;
  const ry0 = bbox ? Math.max(0, Math.floor((bbox.y - bbox.h * expand) * mapH)) : 0;
  const rx1 = bbox ? Math.min(mapW - 1, Math.ceil((bbox.x + bbox.w * (1 + expand)) * mapW)) : mapW - 1;
  const ry1 = bbox ? Math.min(mapH - 1, Math.ceil((bbox.y + bbox.h * (1 + expand)) * mapH)) : mapH - 1;

  // Build region-scoped binary mask and denoise isolated speckles
  const rawBinary = new Uint8Array(planeSize);
  for (let y = ry0; y <= ry1; y++) {
    const row = y * mapW;
    for (let x = rx0; x <= rx1; x++) {
      if (keepSet.has(classMap[row + x])) {
        rawBinary[row + x] = 1;
      }
    }
  }

  const { cleaned, totalPixels, minX: minMX, minY: minMY, maxX: maxMX, maxY: maxMY } = denoiseBinaryMask(
    rawBinary,
    mapW,
    mapH,
    maxComponents,
    minPixels
  );

  if (maxMX <= minMX || maxMY <= minMY || totalPixels < minPixels) {
    return null;
  }

  // Dilate cleaned mask by 1 semantic pixel so soft probability boundary pixels are included cleanly
  const halo = new Uint8Array(planeSize);
  for (let y = Math.max(0, minMY - 1); y <= Math.min(mapH - 1, maxMY + 1); y++) {
    const row = y * mapW;
    for (let x = Math.max(0, minMX - 1); x <= Math.min(mapW - 1, maxMX + 1); x++) {
      const idx = row + x;
      if (
        cleaned[idx] ||
        (x > 0 && cleaned[idx - 1]) ||
        (x + 1 < mapW && cleaned[idx + 1]) ||
        (y > 0 && cleaned[idx - mapW]) ||
        (y + 1 < mapH && cleaned[idx + mapW])
      ) {
        halo[idx] = 1;
      }
    }
  }

  // Build cropped soft-probability mask canvas at semantic map resolution
  const padM = Math.max(2, Math.round(Math.max(maxMX - minMX, maxMY - minMY) * 0.07));
  const mX0 = Math.max(0, minMX - padM);
  const mY0 = Math.max(0, minMY - padM);
  const mX1 = Math.min(mapW - 1, maxMX + padM);
  const mY1 = Math.min(mapH - 1, maxMY + padM);
  const mW = mX1 - mX0 + 1;
  const mH = mY1 - mY0 + 1;

  const maskCanvas = document.createElement('canvas');
  maskCanvas.width = mW;
  maskCanvas.height = mH;
  const mctx = maskCanvas.getContext('2d');
  if (!mctx) return null;
  const maskImgData = mctx.createImageData(mW, mH);

  for (let y = 0; y < mH; y++) {
    const gy = mY0 + y;
    const srcRow = gy * mapW;
    for (let x = 0; x < mW; x++) {
      const gx = mX0 + x;
      const gIdx = srcRow + gx;
      let alpha = 0;
      if (halo[gIdx]) {
        // Sum softmax probabilities across kept classes (0..1)
        let probSum = 0;
        for (const c of keepClasses) {
          probSum += classProbs[c * planeSize + gIdx] / 255;
        }
        if (cleaned[gIdx]) {
          // Interior garment pixel: crisp high alpha with soft edge taper
          alpha = Math.min(255, Math.round(Math.max(0.78, probSum) * 255));
        } else if (probSum > 0.22) {
          // 1px soft boundary halo: smoothstep ramp for natural anti-aliased edge
          const t = Math.max(0, Math.min(1, (probSum - 0.22) / 0.45));
          alpha = Math.round(t * t * (3 - 2 * t) * 255);
        }
      }
      const pIdx = (y * mW + x) * 4;
      maskImgData.data[pIdx] = 255;
      maskImgData.data[pIdx + 1] = 255;
      maskImgData.data[pIdx + 2] = 255;
      maskImgData.data[pIdx + 3] = alpha;
    }
  }
  mctx.putImageData(maskImgData, 0, 0);

  // Map back to original high-resolution source coordinates
  const sx = Math.max(0, Math.floor((mX0 / mapW) * origW));
  const sy = Math.max(0, Math.floor((mY0 / mapH) * origH));
  const sw = Math.max(1, Math.min(origW - sx, Math.ceil((mW / mapW) * origW)));
  const sh = Math.max(1, Math.min(origH - sy, Math.ceil((mH / mapH) * origH)));

  const scale = Math.min(1, maxEdge / Math.max(sw, sh));
  const outW = Math.max(1, Math.round(sw * scale));
  const outH = Math.max(1, Math.round(sh * scale));

  // 1) Crisp unmasked crop (for manual brush Restore mode + originalImageUrl persistence)
  const cropCanvas = document.createElement('canvas');
  cropCanvas.width = outW;
  cropCanvas.height = outH;
  const cropCtx = cropCanvas.getContext('2d');
  if (!cropCtx) return null;
  cropCtx.fillStyle = '#FFFFFF';
  cropCtx.fillRect(0, 0, outW, outH);
  cropCtx.drawImage(image, sx, sy, sw, sh, 0, 0, outW, outH);

  // 2) Smoothly upscaled probability alpha mask with gentle contrast tightening
  const alphaCanvas = document.createElement('canvas');
  alphaCanvas.width = outW;
  alphaCanvas.height = outH;
  const actx = alphaCanvas.getContext('2d', { willReadFrequently: true });
  if (!actx) return null;
  actx.imageSmoothingEnabled = true;
  actx.imageSmoothingQuality = 'high';
  actx.filter = 'blur(0.9px)';
  actx.drawImage(maskCanvas, 0, 0, outW, outH);
  actx.filter = 'none';

  // Tighten low-alpha halo fringing so edges stay razor-clean on pure white gallery cards
  const alphaData = actx.getImageData(0, 0, outW, outH);
  const ad = alphaData.data;
  for (let i = 3; i < ad.length; i += 4) {
    const a = ad[i];
    if (a < 55) ad[i] = 0;
    else if (a > 210) ad[i] = 255;
    else ad[i] = Math.round(((a - 55) / 155) * 255);
  }
  actx.putImageData(alphaData, 0, 0);

  // 3) Composite original RGB with refined garment alpha mask
  const outCanvas = document.createElement('canvas');
  outCanvas.width = outW;
  outCanvas.height = outH;
  const octx = outCanvas.getContext('2d');
  if (!octx) return null;
  octx.drawImage(image, sx, sy, sw, sh, 0, 0, outW, outH);
  octx.globalCompositeOperation = 'destination-in';
  octx.drawImage(alphaCanvas, 0, 0, outW, outH);

  const dominantColor = extractDominantColorFromCanvas(outCanvas);

  const [cutoutBlob, cropBlob] = await Promise.all([
    canvasToBlob(outCanvas, 'image/png'),
    canvasToBlob(cropCanvas, 'image/jpeg', 0.9),
  ]);
  const [cutoutDataUrl, cropDataUrl] = await Promise.all([
    blobToDataUrl(cutoutBlob),
    blobToDataUrl(cropBlob),
  ]);

  const tightBbox: BoundingBox = {
    x: Number((minMX / mapW).toFixed(4)),
    y: Number((minMY / mapH).toFixed(4)),
    w: Number(Math.max(0.02, (maxMX - minMX + 1) / mapW).toFixed(4)),
    h: Number(Math.max(0.02, (maxMY - minMY + 1) / mapH).toFixed(4)),
  };

  return {
    cutoutFile: new File([cutoutBlob], `${filePrefix}-cutout.png`, { type: 'image/png' }),
    cutoutDataUrl,
    cropFile: new File([cropBlob], `${filePrefix}-crop.jpg`, { type: 'image/jpeg' }),
    cropDataUrl,
    matchedClasses: keepClasses,
    coverage: totalPixels / planeSize,
    bbox: tightBbox,
    dominantColor,
  };
}

/**
 * SegFormer-First auto-discovery: scans the 18-class semantic map of ANY street-snap / outfit screenshot
 * and returns the ordered list of real clothing items present (top/dress, pants/skirt, shoes, bag, hat, scarf)
 * with ZERO cloud API dependency.
 */
export function planGarmentsFromSemanticMap(parsed: ParsedClothesMap): DiscoveredGarmentSpec[] {
  const { mapW, mapH, classMap } = parsed;
  const planeSize = mapW * mapH;

  const candidates: Array<{
    id: string;
    category: 'outerwear' | 'top' | 'dress' | 'bottom' | 'shoes' | 'bag' | 'accessory';
    baseName: string;
    svgType: string;
    classes: number[];
    maxComponents: number;
    minRatio: number;
    minPixels: number;
    defaultSeasons: string[];
  }> = [
    {
      id: 'dress',
      category: 'dress',
      baseName: '连衣裙',
      svgType: 'skirt',
      classes: [SEG_LABELS.DRESS],
      maxComponents: 1,
      minRatio: 0.018,
      minPixels: 24,
      defaultSeasons: ['春', '夏', '秋'],
    },
    {
      id: 'upper',
      category: 'top',
      baseName: '上衣',
      svgType: 'tshirt',
      classes: [SEG_LABELS.UPPER_CLOTHES],
      maxComponents: 2,
      minRatio: 0.014,
      minPixels: 22,
      defaultSeasons: ['春', '秋'],
    },
    {
      id: 'pants',
      category: 'bottom',
      baseName: '长裤',
      svgType: 'trousers',
      classes: [SEG_LABELS.PANTS],
      maxComponents: 2,
      minRatio: 0.014,
      minPixels: 22,
      defaultSeasons: ['春', '秋', '冬'],
    },
    {
      id: 'skirt',
      category: 'bottom',
      baseName: '半身裙',
      svgType: 'skirt',
      classes: [SEG_LABELS.SKIRT],
      maxComponents: 1,
      minRatio: 0.013,
      minPixels: 20,
      defaultSeasons: ['春', '夏', '秋'],
    },
    {
      id: 'shoes',
      category: 'shoes',
      baseName: '鞋履',
      svgType: 'loafers',
      classes: [SEG_LABELS.LEFT_SHOE, SEG_LABELS.RIGHT_SHOE],
      maxComponents: 2,
      minRatio: 0.0032,
      minPixels: 12,
      defaultSeasons: ['春', '秋', '冬'],
    },
    {
      id: 'bag',
      category: 'bag',
      baseName: '包袋',
      svgType: 'tote_bag',
      classes: [SEG_LABELS.BAG],
      maxComponents: 1,
      minRatio: 0.0055,
      minPixels: 15,
      defaultSeasons: ['春', '夏', '秋', '冬'],
    },
    {
      id: 'hat',
      category: 'accessory',
      baseName: '帽子',
      svgType: 'tote_bag',
      classes: [SEG_LABELS.HAT],
      maxComponents: 1,
      minRatio: 0.0055,
      minPixels: 15,
      defaultSeasons: ['春', '秋', '冬'],
    },
    {
      id: 'scarf',
      category: 'accessory',
      baseName: '围巾',
      svgType: 'tote_bag',
      classes: [SEG_LABELS.SCARF],
      maxComponents: 1,
      minRatio: 0.0055,
      minPixels: 15,
      defaultSeasons: ['秋', '冬'],
    },
  ];

  const found: DiscoveredGarmentSpec[] = [];
  for (const cand of candidates) {
    const keepSet = new Set(cand.classes);
    const raw = new Uint8Array(planeSize);
    for (let i = 0; i < planeSize; i++) {
      if (keepSet.has(classMap[i])) raw[i] = 1;
    }
    const { totalPixels, minX, minY, maxX, maxY } = denoiseBinaryMask(
      raw,
      mapW,
      mapH,
      cand.maxComponents,
      cand.minPixels
    );
    const ratio = totalPixels / planeSize;
    if (totalPixels < cand.minPixels || ratio < cand.minRatio || maxX <= minX || maxY <= minY) {
      continue;
    }
    found.push({
      id: cand.id,
      category: cand.category,
      baseName: cand.baseName,
      svgType: cand.svgType,
      classes: cand.classes,
      maxComponents: cand.maxComponents,
      bbox: {
        x: Number((minX / mapW).toFixed(4)),
        y: Number((minY / mapH).toFixed(4)),
        w: Number(Math.max(0.03, (maxX - minX + 1) / mapW).toFixed(4)),
        h: Number(Math.max(0.03, (maxY - minY + 1) / mapH).toFixed(4)),
      },
      pixelRatio: ratio,
      defaultSeasons: cand.defaultSeasons,
    });
  }

  // Avoid emitting a tiny skirt/upper fragment when a full dress dominates the body
  const dress = found.find((f) => f.id === 'dress');
  if (dress && dress.pixelRatio >= 0.04) {
    return found.filter((f) => !(f.id === 'skirt' && f.pixelRatio < dress.pixelRatio * 0.35));
  }

  return found;
}

/**
 * Extracts a single planned garment spec into a complete, color-named transparent PNG cutout + crop.
 */
export async function extractDiscoveredGarment(
  parsed: ParsedClothesMap,
  spec: DiscoveredGarmentSpec,
  filePrefix = 'auto'
): Promise<DiscoveredGarmentResult | null> {
  const res = await extractClassGroupMask(parsed, spec.classes, {
    bbox: spec.bbox,
    maxComponents: spec.maxComponents,
    filePrefix: `${filePrefix}-${spec.id}`,
    minPixels: spec.id === 'shoes' ? 10 : 14,
  });
  if (!res) return null;

  const { color, colorHex } = res.dominantColor;
  let finalBase = spec.baseName;
  let finalSvg = spec.svgType;
  if (spec.id === 'pants' && color.includes('蓝')) {
    finalBase = '牛仔裤';
    finalSvg = 'jeans';
  }
  const name = `${color} ${finalBase}`;

  return {
    spec,
    name,
    category: spec.category,
    color,
    colorHex,
    seasons: [...spec.defaultSeasons],
    styleTags: ['日常百搭'],
    svgType: finalSvg,
    bbox: res.bbox,
    cutoutFile: res.cutoutFile,
    cutoutDataUrl: res.cutoutDataUrl,
    cropFile: res.cropFile,
    cropDataUrl: res.cropDataUrl,
  };
}

/**
 * Extracts a single garment from a parsed SegFormer semantic map into a tight-cropped transparent PNG
 * (plus a matching white-backdrop JPEG crop) while stripping human skin, face, hair, and unrelated garments.
 */
export async function extractGarmentBySemanticMask(
  parsed: ParsedClothesMap,
  options: {
    category: string;
    name?: string;
    bbox?: BoundingBox | null;
    maxEdge?: number;
    filePrefix?: string;
  }
): Promise<SemanticCutoutResult | null> {
  const { category, name = '', bbox = null, maxEdge = 1024, filePrefix = 'seg' } = options;
  const { mapW, mapH, classMap } = parsed;

  const coreX0 = bbox ? Math.max(0, Math.floor(bbox.x * mapW)) : 0;
  const coreY0 = bbox ? Math.max(0, Math.floor(bbox.y * mapH)) : 0;
  const coreX1 = bbox ? Math.min(mapW - 1, Math.ceil((bbox.x + bbox.w) * mapW)) : mapW - 1;
  const coreY1 = bbox ? Math.min(mapH - 1, Math.ceil((bbox.y + bbox.h) * mapH)) : mapH - 1;
  const coreArea = Math.max(1, (coreX1 - coreX0 + 1) * (coreY1 - coreY0 + 1));

  // Choose the candidate class group with best pixel support inside the core box
  const groups = candidateClassGroups(category, name);
  let chosenGroup: number[] = [];
  let bestCount = 0;

  for (const grp of groups) {
    const set = new Set(grp);
    let cnt = 0;
    for (let y = coreY0; y <= coreY1; y++) {
      const row = y * mapW;
      for (let x = coreX0; x <= coreX1; x++) {
        if (set.has(classMap[row + x])) cnt += 1;
      }
    }
    if (cnt > bestCount) {
      bestCount = cnt;
      chosenGroup = grp;
    }
  }

  // Fallback: if category-specific class had almost no support inside bbox, pick dominant garment class inside bbox
  if (bestCount < coreArea * 0.04 && bbox) {
    const localCounts = new Int32Array(18);
    for (let y = coreY0; y <= coreY1; y++) {
      const row = y * mapW;
      for (let x = coreX0; x <= coreX1; x++) {
        const c = classMap[row + x];
        if (ALL_GARMENT_CLASSES.has(c)) localCounts[c] += 1;
      }
    }
    let domClass = -1;
    let domCount = 0;
    for (let c = 1; c < 18; c++) {
      if (localCounts[c] > domCount) {
        domCount = localCounts[c];
        domClass = c;
      }
    }
    if (domClass > 0 && domCount >= coreArea * 0.04) {
      chosenGroup =
        domClass === SEG_LABELS.LEFT_SHOE || domClass === SEG_LABELS.RIGHT_SHOE
          ? [SEG_LABELS.LEFT_SHOE, SEG_LABELS.RIGHT_SHOE]
          : [domClass];
      bestCount = domCount;
    }
  }

  // If still too few pixels, signal caller to fall back to general cutout engine
  const minRatio = bbox ? 0.035 : 0.012;
  if (chosenGroup.length === 0 || bestCount < coreArea * minRatio) {
    return null;
  }

  return extractClassGroupMask(parsed, chosenGroup, {
    bbox,
    maxComponents: category === 'shoes' ? 2 : 2,
    maxEdge,
    filePrefix,
  });
}
