import type { BoundingBox } from '@/types';

/**
 * Self-hosted browser-side SegFormer-B2-Clothes (18-class apparel semantic segmentation).
 * Model is served from our own origin (/models/segformer_b2_clothes/model_quantized.onnx, ~27.5MB q8)
 * and ONNX Runtime WASM from /ort/ so Chinese users never stall on HuggingFace / jsDelivr CDNs.
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
  classMap: Uint8Array;
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
 * Runs SegFormer-B2-Clothes once on the full source photo and returns a reusable 18-class semantic map.
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
      const classCounts = new Int32Array(numClasses);

      for (let p = 0; p < planeSize; p++) {
        let bestC = 0;
        let bestVal = outData[p];
        for (let c = 1; c < numClasses; c++) {
          const v = outData[c * planeSize + p];
          if (v > bestVal) {
            bestVal = v;
            bestC = c;
          }
        }
        classMap[p] = bestC;
        classCounts[bestC] += 1;
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

      const hasHumanWearing = humanPixels > planeSize * 0.008 || distinctGarmentClasses >= 2;
      const garmentRatio = garmentPixels / planeSize;

      return {
        image: img,
        origW,
        origH,
        mapW,
        mapH,
        classMap,
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
    case 'accessory':
      if (/帽/.test(n)) return [[SEG_LABELS.HAT]];
      if (/墨镜|太阳镜|眼镜/.test(n)) return [[SEG_LABELS.SUNGLASSES]];
      if (/腰带|皮带/.test(n)) return [[SEG_LABELS.BELT]];
      if (/围巾|丝巾|披肩/.test(n)) return [[SEG_LABELS.SCARF], [SEG_LABELS.UPPER_CLOTHES]];
      if (/包|托特|腋下|斜挎|手提/.test(n)) return [[SEG_LABELS.BAG]];
      return [[SEG_LABELS.BAG], [SEG_LABELS.SCARF], [SEG_LABELS.HAT], [SEG_LABELS.SUNGLASSES], [SEG_LABELS.BELT]];
    default:
      return [[SEG_LABELS.UPPER_CLOTHES], [SEG_LABELS.DRESS], [SEG_LABELS.PANTS], [SEG_LABELS.SKIRT]];
  }
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
  const { mapW, mapH, classMap, origW, origH, image } = parsed;

  // Define search window in semantic map coordinates
  const expand = 0.14;
  const rx0 = bbox ? Math.max(0, Math.floor((bbox.x - bbox.w * expand) * mapW)) : 0;
  const ry0 = bbox ? Math.max(0, Math.floor((bbox.y - bbox.h * expand) * mapH)) : 0;
  const rx1 = bbox ? Math.min(mapW - 1, Math.ceil((bbox.x + bbox.w * (1 + expand)) * mapW)) : mapW - 1;
  const ry1 = bbox ? Math.min(mapH - 1, Math.ceil((bbox.y + bbox.h * (1 + expand)) * mapH)) : mapH - 1;

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

  const keepSet = new Set(chosenGroup);

  // Compute tight bounding box of the selected semantic mask within the expanded region
  let minMX = mapW;
  let minMY = mapH;
  let maxMX = -1;
  let maxMY = -1;
  let totalMaskPixels = 0;

  for (let y = ry0; y <= ry1; y++) {
    const row = y * mapW;
    for (let x = rx0; x <= rx1; x++) {
      if (keepSet.has(classMap[row + x])) {
        totalMaskPixels += 1;
        if (x < minMX) minMX = x;
        if (x > maxMX) maxMX = x;
        if (y < minMY) minMY = y;
        if (y > maxMY) maxMY = y;
      }
    }
  }

  if (maxMX <= minMX || maxMY <= minMY || totalMaskPixels < 12) {
    return null;
  }

  // Build cropped mask canvas at semantic map resolution (with 2px margin for soft feathering)
  const padM = Math.max(2, Math.round(Math.max(maxMX - minMX, maxMY - minMY) * 0.06));
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
    const srcRow = (mY0 + y) * mapW;
    for (let x = 0; x < mW; x++) {
      const gx = mX0 + x;
      const gy = mY0 + y;
      const inSearch = gx >= rx0 && gx <= rx1 && gy >= ry0 && gy <= ry1;
      const hit = inSearch && keepSet.has(classMap[srcRow + gx]);
      const pIdx = (y * mW + x) * 4;
      maskImgData.data[pIdx] = 255;
      maskImgData.data[pIdx + 1] = 255;
      maskImgData.data[pIdx + 2] = 255;
      maskImgData.data[pIdx + 3] = hit ? 255 : 0;
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

  // 2) Smoothly feathered alpha mask scaled to output size
  const alphaCanvas = document.createElement('canvas');
  alphaCanvas.width = outW;
  alphaCanvas.height = outH;
  const actx = alphaCanvas.getContext('2d');
  if (!actx) return null;
  actx.imageSmoothingEnabled = true;
  actx.imageSmoothingQuality = 'high';
  actx.filter = 'blur(1.2px)';
  actx.drawImage(maskCanvas, 0, 0, outW, outH);

  // 3) Composite original RGB with SegFormer garment alpha mask
  const outCanvas = document.createElement('canvas');
  outCanvas.width = outW;
  outCanvas.height = outH;
  const octx = outCanvas.getContext('2d');
  if (!octx) return null;
  octx.drawImage(image, sx, sy, sw, sh, 0, 0, outW, outH);
  octx.globalCompositeOperation = 'destination-in';
  octx.drawImage(alphaCanvas, 0, 0, outW, outH);

  const [cutoutBlob, cropBlob] = await Promise.all([
    canvasToBlob(outCanvas, 'image/png'),
    canvasToBlob(cropCanvas, 'image/jpeg', 0.9),
  ]);
  const [cutoutDataUrl, cropDataUrl] = await Promise.all([
    blobToDataUrl(cutoutBlob),
    blobToDataUrl(cropBlob),
  ]);

  return {
    cutoutFile: new File([cutoutBlob], `${filePrefix}-cutout.png`, { type: 'image/png' }),
    cutoutDataUrl,
    cropFile: new File([cropBlob], `${filePrefix}-crop.jpg`, { type: 'image/jpeg' }),
    cropDataUrl,
    matchedClasses: chosenGroup,
    coverage: bestCount / coreArea,
  };
}
