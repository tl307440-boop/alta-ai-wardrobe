<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { visionApi } from '@/api/modules';
import { useAuthStore } from '@/stores/auth';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useToastStore } from '@/stores/toast';
import { tryBrowserCutout, trySmartSingleCutout, type CutoutEngineMode } from '@/utils/cutout';
import {
  disposeParsedClothesMap,
  extractDiscoveredGarment,
  extractGarmentBySemanticMask,
  parseClothesSemanticMap,
  planGarmentsFromSemanticMap,
  type ParsedClothesMap,
} from '@/utils/segformer';
import {
  ALL_STYLE_OPTIONS,
  CATEGORY_OPTIONS,
  COLOR_SWATCHES,
  INSPIRATION_PRESETS,
  SCAN_OUTFIT_SAMPLE,
  SCAN_SAMPLES,
  SEASON_OPTIONS,
  categoryLabel,
  platformBadgeClass,
} from '@/utils/constants';
import { cropImageRegion } from '@/utils/crop';
import type { BoundingBox, DetectedItem, InspirationItemMatch, ItemsDetectionResult } from '@/types';
import AppIcon from '@/components/AppIcon.vue';
import GarmentSvg from '@/components/GarmentSvg.vue';
import GarmentVisual from '@/components/GarmentVisual.vue';
import CutoutMaskEditorModal from '@/components/CutoutMaskEditorModal.vue';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const wardrobeStore = useWardrobeStore();
const toast = useToastStore();

const scanMode = ref<'item' | 'inspiration'>(
  route.query.mode === 'inspiration' ? 'inspiration' : 'item'
);

watch(
  () => route.query.mode,
  (m) => {
    if (m === 'inspiration' || m === 'item') scanMode.value = m;
  }
);

// ==================== 模式 A：截图 / 穿搭照一键拆衣抠图分类（Acloset 风格） ====================
interface SamplePiece {
  svgType: string;
  name: string;
  category: string;
  color: string;
  colorHex: string;
  seasons: string[];
  styleTags: string[];
  price?: number;
}
interface ScanSample {
  id: string;
  shortLabel: string;
  sourceLabel: string;
  sourceType: string;
  pieces: SamplePiece[];
}

interface ScanPiece {
  uid: string;
  selected: boolean;
  saved: boolean;
  expanded: boolean;
  moreOpen: boolean;
  corrected: boolean;
  newTag: string;
  name: string;
  category: string;
  color: string;
  colorHex: string;
  seasons: string[];
  styleTags: string[];
  material: string;
  price: string | number;
  svgType: string;
  originalImageUrl: string;
  processedImageUrl: string;
  aiRawResult: string;
  duplicateOf: string | null;
  cropFile: File | null;
  cropDataUrl: string | null;
  bbox: BoundingBox | null;
  cutoutFile: File | null;
  cutoutStatus: 'none' | 'pending' | 'running' | 'done' | 'failed';
  cutoutEngine: 'segformer' | 'general' | 'manual' | 'none';
}

const SAMPLES: ScanSample[] = [
  SCAN_OUTFIT_SAMPLE,
  ...SCAN_SAMPLES.map((s) => ({
    id: s.id,
    shortLabel: s.shortLabel,
    sourceLabel: s.sourceLabel,
    sourceType: s.sourceType,
    pieces: [
      {
        svgType: s.svgType,
        name: s.name,
        category: s.category,
        color: s.color,
        colorHex: s.colorHex,
        seasons: s.seasons,
        styleTags: s.styleTags,
        price: s.category === 'outerwear' ? 329 : s.category === 'bottom' ? 259 : 169,
      },
    ],
  })),
];

const DEFAULT_SVG_BY_CATEGORY: Record<string, string> = {
  outerwear: 'trench',
  top: 'tshirt',
  dress: 'pleated_skirt',
  bottom: 'trousers',
  shoes: 'sneakers',
  bag: 'tote_bag',
  accessory: 'tote_bag',
};

const fileInputRef = ref<HTMLInputElement | null>(null);
const selectedFile = ref<File | null>(null);
const savingItem = ref(false);
const cutoutEngineMode = ref<CutoutEngineMode>('auto');
const detailViewMode = ref(false);

const CUTOUT_ENGINE_OPTIONS: Array<{ key: CutoutEngineMode; label: string; hint: string }> = [
  { key: 'auto', label: '自动', hint: '人像/截图优先本地 SegFormer 自动拆衣抠图，平铺单品走通用抠图' },
  { key: 'segformer', label: '人像拆衣', hint: '强制使用本地 SegFormer-B2-Clothes 18 类去人像拆衣' },
  { key: 'general', label: '单品抠图', hint: '强制使用通用前景抠图，适合平铺/挂拍单品' },
];

type ScanPhase = 'idle' | 'bg' | 'category' | 'cutout' | 'finalize' | 'done';

const maskEditor = reactive({
  open: false,
  targetUid: '' as string,
  title: '',
  originalUrl: '',
  cutoutUrl: '',
});

const itemScan = reactive({
  sample: null as ScanSample | null,
  uploadedOriginalDataUrl: null as string | null,
  uploadedCutoutDataUrl: null as string | null,
  isScanning: false,
  phase: 'idle' as ScanPhase,
  done: false,
  viewMode: 'original' as 'original' | 'cutout',
  usedRealLlm: false,
  notice: '',
  pieces: [] as ScanPiece[],
});

const phaseBannerText = computed(() => {
  switch (itemScan.phase) {
    case 'bg':
      return '正在移除背景…';
    case 'category':
      return '正在识别类别…';
    case 'cutout':
      return '正在逐件抠图分类…';
    case 'finalize':
      return '正在准备分析结果…';
    default:
      return '';
  }
});

let pieceSeq = 0;

function makePiece(partial: Partial<ScanPiece> & Pick<ScanPiece, 'name' | 'category' | 'svgType'>): ScanPiece {
  pieceSeq += 1;
  return {
    uid: `piece-${Date.now()}-${pieceSeq}`,
    selected: !partial.duplicateOf,
    saved: false,
    expanded: false,
    moreOpen: false,
    corrected: false,
    newTag: '',
    color: '燕麦',
    colorHex: '#DFD3C3',
    seasons: ['春', '秋'],
    styleTags: ['日常百搭'],
    material: '',
    price: '',
    originalImageUrl: '',
    processedImageUrl: `svg://${partial.svgType}`,
    aiRawResult: '',
    duplicateOf: null,
    cropFile: null,
    cropDataUrl: null,
    bbox: null,
    cutoutFile: null,
    cutoutStatus: 'none',
    cutoutEngine: 'none',
    ...partial,
  };
}

let scanRunId = 0;
const cutoutWaiters = new Map<string, { promise: Promise<void>; resolve: () => void }>();

function registerCutoutWaiter(uid: string) {
  let resolve: () => void = () => {};
  const promise = new Promise<void>((r) => {
    resolve = r;
  });
  cutoutWaiters.set(uid, { promise, resolve });
}

function settleCutout(uid: string) {
  cutoutWaiters.get(uid)?.resolve();
}

function bboxIoU(a: BoundingBox | null, b: BoundingBox | null): number {
  if (!a || !b) return 0;
  const x0 = Math.max(a.x, b.x);
  const y0 = Math.max(a.y, b.y);
  const x1 = Math.min(a.x + a.w, b.x + b.w);
  const y1 = Math.min(a.y + a.h, b.y + b.h);
  const inter = Math.max(0, x1 - x0) * Math.max(0, y1 - y0);
  if (inter <= 0) return 0;
  const union = a.w * a.h + b.w * b.h - inter;
  return union > 0 ? inter / union : 0;
}

function categoriesCompatible(segCat: string, vlmCat: string): boolean {
  if (segCat === vlmCat) return true;
  // SegFormer groups outerwear + top inside UPPER_CLOTHES
  if ((segCat === 'top' || segCat === 'outerwear') && (vlmCat === 'top' || vlmCat === 'outerwear')) {
    return true;
  }
  if ((segCat === 'bag' || segCat === 'accessory') && (vlmCat === 'bag' || vlmCat === 'accessory')) {
    return true;
  }
  return false;
}

function categoryIconName(cat: string): string {
  switch (cat) {
    case 'outerwear':
    case 'top':
    case 'dress':
      return 'shirt';
    case 'bottom':
      return 'layers';
    case 'shoes':
      return 'sparkles';
    case 'bag':
      return 'shopping-bag';
    case 'accessory':
      return 'sparkles';
    default:
      return 'tag';
  }
}

async function runPieceCutouts(runId: number, sourceFile: File | null = selectedFile.value) {
  const targets = itemScan.pieces.filter((p) => !p.saved && (p.cropFile || p.bbox));
  if (!targets.length) return;

  let parsedMap: ParsedClothesMap | null = null;
  const wantSegFormer =
    sourceFile &&
    (cutoutEngineMode.value === 'segformer' || cutoutEngineMode.value === 'auto');

  if (wantSegFormer && sourceFile) {
    targets.forEach((p) => {
      p.cutoutStatus = 'running';
    });
    try {
      parsedMap = await parseClothesSemanticMap(sourceFile, 45000);
    } catch {
      parsedMap = null;
    }
  }

  let firstGeneral = true;
  try {
    for (const p of targets) {
      if (runId !== scanRunId) break;
      p.cutoutStatus = 'running';

      let extractedBySeg = false;
      if (
        parsedMap &&
        (cutoutEngineMode.value === 'segformer' ||
          (cutoutEngineMode.value === 'auto' && parsedMap.hasHumanWearing))
      ) {
        const segRes = await extractGarmentBySemanticMask(parsedMap, {
          category: p.category,
          name: p.name,
          bbox: p.bbox,
          filePrefix: p.uid,
        });
        if (runId !== scanRunId) break;
        if (segRes) {
          p.cropFile = segRes.cropFile;
          p.cropDataUrl = segRes.cropDataUrl;
          p.cutoutFile = segRes.cutoutFile;
          if (!p.saved) p.processedImageUrl = segRes.cutoutDataUrl;
          p.cutoutStatus = 'done';
          p.cutoutEngine = 'segformer';
          extractedBySeg = true;
        }
      }

      if (!extractedBySeg && p.cropFile) {
        const res = await tryBrowserCutout(p.cropFile, firstGeneral ? 45000 : 20000);
        firstGeneral = false;
        if (runId !== scanRunId) break;
        if (res.cutoutFile && res.cutoutDataUrl) {
          p.cutoutFile = res.cutoutFile;
          if (!p.saved) p.processedImageUrl = res.cutoutDataUrl;
          p.cutoutStatus = 'done';
          p.cutoutEngine = 'general';
        } else {
          p.cutoutStatus = 'failed';
          p.cutoutEngine = 'none';
        }
      } else if (!extractedBySeg) {
        p.cutoutStatus = 'failed';
        p.cutoutEngine = 'none';
      }
      settleCutout(p.uid);
    }
  } finally {
    disposeParsedClothesMap(parsedMap);
    targets.forEach((p) => settleCutout(p.uid));
  }
}

async function switchCutoutEngine(mode: CutoutEngineMode) {
  if (cutoutEngineMode.value === mode) return;
  cutoutEngineMode.value = mode;
  if (!selectedFile.value || !itemScan.done || itemScan.isScanning) return;
  const runId = ++scanRunId;
  cutoutWaiters.forEach((w) => w.resolve());
  cutoutWaiters.clear();

  if (itemScan.pieces.length >= 2) {
    itemScan.pieces.forEach((p) => {
      if (!p.saved && (p.cropFile || p.bbox)) {
        p.cutoutStatus = 'pending';
        registerCutoutWaiter(p.uid);
      }
    });
    toast.show(`已切换为「${CUTOUT_ENGINE_OPTIONS.find((o) => o.key === mode)?.label}」，正在重新抠图…`);
    void runPieceCutouts(runId, selectedFile.value);
    return;
  }

  const p = itemScan.pieces[0];
  if (p && !p.saved) {
    p.cutoutStatus = 'running';
    toast.show(`已切换为「${CUTOUT_ENGINE_OPTIONS.find((o) => o.key === mode)?.label}」，正在重新抠图…`);
    const res = await trySmartSingleCutout(selectedFile.value, mode, p.category, p.name, 30000);
    if (runId !== scanRunId) return;
    if (res.cutoutFile && res.cutoutDataUrl) {
      p.cropFile = selectedFile.value;
      p.cropDataUrl = itemScan.uploadedOriginalDataUrl;
      p.cutoutFile = res.cutoutFile;
      p.processedImageUrl = res.cutoutDataUrl;
      p.cutoutStatus = 'done';
      p.cutoutEngine = res.engineUsed;
      itemScan.uploadedCutoutDataUrl = res.cutoutDataUrl;
      itemScan.viewMode = 'cutout';
    } else {
      p.cutoutStatus = 'failed';
      toast.show('切换引擎抠图未命中，已保留当前预览');
    }
  }
}

function openMaskEditorForPiece(p: ScanPiece) {
  if (p.saved) return;
  const orig = p.cropDataUrl || itemScan.uploadedOriginalDataUrl || p.originalImageUrl;
  const cut = p.processedImageUrl.startsWith('svg://') ? orig : p.processedImageUrl;
  if (!orig && !cut) {
    toast.show('当前示例图不支持画笔精修，请上传实物照片');
    return;
  }
  maskEditor.targetUid = p.uid;
  maskEditor.title = p.name;
  maskEditor.originalUrl = orig || cut;
  maskEditor.cutoutUrl = cut || orig;
  maskEditor.open = true;
}

function handleMaskEditorApply(payload: { file: File; dataUrl: string }) {
  const p = itemScan.pieces.find((x) => x.uid === maskEditor.targetUid);
  if (p) {
    if (!p.cropFile && selectedFile.value) {
      p.cropFile = selectedFile.value;
      p.cropDataUrl = itemScan.uploadedOriginalDataUrl;
    }
    p.cutoutFile = payload.file;
    p.processedImageUrl = payload.dataUrl;
    p.cutoutStatus = 'done';
    p.cutoutEngine = 'manual';
    p.corrected = true;
    if (itemScan.pieces.length === 1) {
      itemScan.uploadedCutoutDataUrl = payload.dataUrl;
      itemScan.viewMode = 'cutout';
    }
    toast.show(`已应用「${p.name}」手动精修抠图`);
  }
  maskEditor.open = false;
}

async function waitForPieceCutout(p: ScanPiece) {
  if (p.cutoutStatus !== 'pending' && p.cutoutStatus !== 'running') return;
  await cutoutWaiters.get(p.uid)?.promise;
}

const hasSource = computed(() => !!(itemScan.sample || itemScan.uploadedOriginalDataUrl));
const selectablePieces = computed(() => itemScan.pieces.filter((p) => !p.saved));
const selectedCount = computed(() => selectablePieces.value.filter((p) => p.selected).length);
const allSelected = computed(
  () => selectablePieces.value.length > 0 && selectedCount.value === selectablePieces.value.length
);
const allSaved = computed(() => itemScan.pieces.length > 0 && itemScan.pieces.every((p) => p.saved));

function resetScanResult() {
  scanRunId += 1;
  cutoutWaiters.forEach((w) => w.resolve());
  cutoutWaiters.clear();
  itemScan.done = false;
  itemScan.phase = 'idle';
  itemScan.pieces = [];
  itemScan.notice = '';
  itemScan.usedRealLlm = false;
  itemScan.uploadedCutoutDataUrl = null;
  itemScan.viewMode = 'original';
}

function triggerFileUpload() {
  fileInputRef.value?.click();
}

function handleFileUpload(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  if (!file.type.startsWith('image/')) {
    toast.show('格式校验失败：仅支持上传 JPG / PNG / WEBP 图片文件');
    return;
  }
  if (file.size > 10 * 1024 * 1024) {
    toast.show('图片体积超过 10MB 上限，请压缩后重试');
    return;
  }
  selectedFile.value = file;
  itemScan.sample = null;
  resetScanResult();
  const reader = new FileReader();
  reader.onload = (ev) => {
    itemScan.uploadedOriginalDataUrl = String(ev.target?.result || '');
    void startItemScan();
  };
  reader.readAsDataURL(file);
  input.value = '';
}

function selectScanSample(sample: ScanSample) {
  if (itemScan.isScanning) return;
  selectedFile.value = null;
  itemScan.sample = sample;
  itemScan.uploadedOriginalDataUrl = null;
  resetScanResult();
  void startItemScan();
}

function inferSeasons(category: string, name: string): string[] {
  if (/羽绒|棉服|大衣|毛衣|马甲|加绒|靴/.test(name)) return ['秋', '冬'];
  if (/短袖|t恤|背心|短裤|半裙|凉鞋|吊带/i.test(name)) return ['春', '夏'];
  if (category === 'bag' || category === 'accessory') return [...SEASON_OPTIONS];
  return ['春', '秋'];
}

function pieceFromDetected(d: DetectedItem, sourceUrl: string): ScanPiece {
  const category = d.category || 'top';
  const svgType = d.svgType || DEFAULT_SVG_BY_CATEGORY[category] || 'tshirt';
  return makePiece({
    name: d.name || '识别单品',
    category,
    color: d.color || '燕麦',
    colorHex: d.colorHex || '#DFD3C3',
    seasons: d.seasons?.length ? [...d.seasons] : inferSeasons(category, d.name || ''),
    styleTags: d.styleTags?.length ? [...d.styleTags] : ['日常百搭'],
    material: d.material || '',
    svgType,
    originalImageUrl: sourceUrl,
    processedImageUrl: `svg://${svgType}`,
    aiRawResult: d.aiRawResult || '',
    duplicateOf: (d.similarity ?? 0) >= 92 ? (d.similarWardrobeName ?? null) : null,
    bbox: d.bbox ?? null,
  });
}

function removeSelectedPieces() {
  const toRemove = selectablePieces.value.filter((p) => p.selected);
  if (!toRemove.length) return;
  const removeIds = new Set(toRemove.map((p) => p.uid));
  itemScan.pieces = itemScan.pieces.filter((p) => !removeIds.has(p.uid));
  toast.show(`已移除 ${toRemove.length} 件单品`);
}

/**
 * SegFormer-First + Parallel Cloud VLM Hybrid Pipeline (Acloset-style):
 * 1) Runs local SegFormer-B2-Clothes immediately so ANY street-snap / phone screenshot splits into
 *    independent cutout garments (top, pants/skirt, shoes, bag, hat, scarf) even with ZERO API Key!
 * 2) Runs cloud VLM `detect-items` in parallel when configured; merges fine-grained names, outerwear/top
 *    distinction, seasons, style tags, and any layered extras seamlessly onto the local cutouts.
 * 3) Flat-lay single-garment photos (no human wearing & <= 1 semantic region) gracefully route through
 *    @imgly general foreground cutout + single-item recognition.
 */
async function scanUploadedPhoto(file: File) {
  const runId = scanRunId;
  const cfg = wardrobeStore.aiConfig;
  const llmReady = cfg.enabled && (cfg.hasApiKey || cfg.serverDefaultAvailable);

  // Kick off cloud multi-garment VLM detection in parallel if active (never blocks local SegFormer!)
  const vlmPromise: Promise<ItemsDetectionResult | null> = llmReady
    ? (async () => {
        try {
          const fd = new FormData();
          fd.append('file', file);
          fd.append('sourceType', 'photo');
          return await visionApi.detectItems(fd);
        } catch {
          return null;
        }
      })()
    : Promise.resolve(null);

  // Phase 1: Local SegFormer semantic discovery (skip only if user explicitly forced 'general')
  itemScan.phase = 'bg';
  let parsed: ParsedClothesMap | null = null;
  try {
    if (cutoutEngineMode.value !== 'general') {
      try {
        parsed = await parseClothesSemanticMap(file, 45000);
      } catch {
        parsed = null;
      }
      if (runId !== scanRunId) return;

      if (parsed) {
        itemScan.phase = 'category';
        const specs = planGarmentsFromSemanticMap(parsed);
        const shouldUseSegMulti =
          specs.length >= 2 ||
          (specs.length === 1 && (parsed.hasHumanWearing || cutoutEngineMode.value === 'segformer'));

        if (shouldUseSegMulti) {
          // Immediately populate rows with cropped previews + diagonal shimmer sweep (like 2.mp4 00:07)
          itemScan.phase = 'cutout';
          const initialPieces: ScanPiece[] = [];
          for (let i = 0; i < specs.length; i++) {
            const spec = specs[i];
            let cropPreviewUrl = `svg://${spec.svgType}`;
            let cropFile: File | null = null;
            let cropDataUrl: string | null = null;
            try {
              const c = await cropImageRegion(file, spec.bbox, { name: `pre-${spec.id}` });
              cropPreviewUrl = c.dataUrl;
              cropFile = c.file;
              cropDataUrl = c.dataUrl;
            } catch {
              // Keep svg fallback while extracting
            }
            const piece = makePiece({
              name: spec.baseName,
              category: spec.category,
              svgType: spec.svgType,
              seasons: [...spec.defaultSeasons],
              cropFile,
              cropDataUrl,
              processedImageUrl: cropPreviewUrl,
              bbox: spec.bbox,
              cutoutStatus: 'running',
            });
            registerCutoutWaiter(piece.uid);
            initialPieces.push(piece);
          }

          if (runId !== scanRunId) return;
          itemScan.pieces = initialPieces;

          // Sequentially finalize each transparent cutout + real pixel dominant color
          for (let i = 0; i < specs.length; i++) {
            if (runId !== scanRunId) return;
            const piece = initialPieces[i];
            const extracted = await extractDiscoveredGarment(parsed, specs[i], piece.uid);
            if (runId !== scanRunId) return;
            if (extracted) {
              piece.name = extracted.name;
              piece.category = extracted.category;
              piece.color = extracted.color;
              piece.colorHex = extracted.colorHex;
              piece.seasons = extracted.seasons;
              piece.svgType = extracted.svgType;
              piece.bbox = extracted.bbox;
              piece.cropFile = extracted.cropFile;
              piece.cropDataUrl = extracted.cropDataUrl;
              piece.cutoutFile = extracted.cutoutFile;
              piece.processedImageUrl = extracted.cutoutDataUrl;
              piece.cutoutStatus = 'done';
              piece.cutoutEngine = 'segformer';
            } else {
              piece.cutoutStatus = 'failed';
            }
            settleCutout(piece.uid);
          }

          // Phase 3: Merge parallel VLM semantic enrichment (finer names, outerwear vs top, style tags, layered pieces)
          if (llmReady) {
            itemScan.phase = 'finalize';
            const vlmRes = await vlmPromise;
            if (runId !== scanRunId) return;
            const vlmItems = vlmRes?.usedRealLlm ? vlmRes.items || [] : [];
            if (vlmItems.length > 0) {
              const matchedPieceUids = new Set<string>();
              const unmatchedVlm: DetectedItem[] = [];

              for (const d of vlmItems) {
                let bestPiece: ScanPiece | null = null;
                let bestScore = -1;
                for (const p of initialPieces) {
                  if (matchedPieceUids.has(p.uid)) continue;
                  const compat = categoriesCompatible(p.category, d.category);
                  const iou = bboxIoU(p.bbox, d.bbox);
                  const score = (compat ? 1.0 : 0.0) + iou * 2.0;
                  if ((compat || iou > 0.25) && score > bestScore) {
                    bestScore = score;
                    bestPiece = p;
                  }
                }
                if (bestPiece) {
                  matchedPieceUids.add(bestPiece.uid);
                  bestPiece.name = d.name || bestPiece.name;
                  bestPiece.category = d.category || bestPiece.category;
                  bestPiece.color = d.color || bestPiece.color;
                  bestPiece.colorHex = d.colorHex || bestPiece.colorHex;
                  bestPiece.seasons = d.seasons?.length ? [...d.seasons] : bestPiece.seasons;
                  bestPiece.styleTags = d.styleTags?.length ? [...d.styleTags] : bestPiece.styleTags;
                  bestPiece.material = d.material || bestPiece.material;
                  bestPiece.svgType = d.svgType || bestPiece.svgType;
                  bestPiece.aiRawResult = d.aiRawResult || '';
                  bestPiece.originalImageUrl = vlmRes?.originalImageUrl || bestPiece.originalImageUrl;
                  bestPiece.duplicateOf = (d.similarity ?? 0) >= 92 ? (d.similarWardrobeName ?? null) : null;
                  if (bestPiece.duplicateOf) bestPiece.selected = false;
                } else {
                  unmatchedVlm.push(d);
                }
              }

              // Add extra layered/accessory pieces detected by VLM (e.g. jacket over shirt)
              for (const extra of unmatchedVlm) {
                if (!extra.bbox) continue;
                const extraPiece = pieceFromDetected(extra, vlmRes?.originalImageUrl || '');
                try {
                  const c = await cropImageRegion(file, extra.bbox, { name: `vlm-${extraPiece.uid}` });
                  extraPiece.cropFile = c.file;
                  extraPiece.cropDataUrl = c.dataUrl;
                  extraPiece.processedImageUrl = c.dataUrl;
                  extraPiece.cutoutStatus = 'running';
                  registerCutoutWaiter(extraPiece.uid);
                  itemScan.pieces.push(extraPiece);

                  const segExtra = await extractGarmentBySemanticMask(parsed, {
                    category: extraPiece.category,
                    name: extraPiece.name,
                    bbox: extraPiece.bbox,
                    filePrefix: extraPiece.uid,
                  });
                  if (segExtra) {
                    extraPiece.cutoutFile = segExtra.cutoutFile;
                    extraPiece.processedImageUrl = segExtra.cutoutDataUrl;
                    extraPiece.cutoutStatus = 'done';
                    extraPiece.cutoutEngine = 'segformer';
                  } else {
                    const gen = await tryBrowserCutout(c.file, 20000);
                    if (gen.cutoutFile && gen.cutoutDataUrl) {
                      extraPiece.cutoutFile = gen.cutoutFile;
                      extraPiece.processedImageUrl = gen.cutoutDataUrl;
                      extraPiece.cutoutStatus = 'done';
                      extraPiece.cutoutEngine = 'general';
                    } else {
                      extraPiece.cutoutStatus = 'failed';
                    }
                  }
                  settleCutout(extraPiece.uid);
                } catch {
                  settleCutout(extraPiece.uid);
                }
              }

              itemScan.usedRealLlm = true;
              itemScan.notice = `已自动拆分抠图并精准识别 ${itemScan.pieces.length} 件单品`;
            } else {
              itemScan.usedRealLlm = false;
              itemScan.notice = `已通过本地视觉分割自动拆出 ${itemScan.pieces.length} 件单品并完成抠图`;
            }
          } else {
            itemScan.usedRealLlm = false;
            itemScan.notice = `已通过本地视觉分割自动拆出 ${itemScan.pieces.length} 件单品并完成抠图`;
          }

          if (itemScan.pieces.length === 1 && itemScan.pieces[0].cutoutFile) {
            itemScan.uploadedCutoutDataUrl = itemScan.pieces[0].processedImageUrl;
            itemScan.viewMode = 'cutout';
          }
          itemScan.phase = 'done';
          itemScan.done = true;
          return;
        }
      }
    }
  } finally {
    disposeParsedClothesMap(parsed);
  }

  // Fallback Path A: Cloud VLM detected >= 2 items (e.g. collage/flat-lay grid of multiple clothes without human)
  if (llmReady) {
    itemScan.phase = 'category';
    const vlmRes = await vlmPromise;
    if (runId !== scanRunId) return;
    const detected = vlmRes?.usedRealLlm ? vlmRes.items || [] : [];
    if (detected.length >= 2) {
      itemScan.phase = 'cutout';
      const pieces = detected.map((d) => pieceFromDetected(d, vlmRes?.originalImageUrl || ''));
      await Promise.all(
        detected.map(async (d, i) => {
          if (!d.bbox) return;
          try {
            const crop = await cropImageRegion(file, d.bbox, { name: `piece-${i + 1}` });
            pieces[i].cropFile = crop.file;
            pieces[i].cropDataUrl = crop.dataUrl;
            pieces[i].processedImageUrl = crop.dataUrl;
            pieces[i].cutoutStatus = 'pending';
            registerCutoutWaiter(pieces[i].uid);
          } catch {
            // Keep placeholder
          }
        })
      );
      if (runId !== scanRunId) return;
      itemScan.usedRealLlm = true;
      itemScan.notice = `已自动识别出 ${detected.length} 件单品`;
      itemScan.pieces = pieces;
      itemScan.phase = 'done';
      itemScan.done = true;
      void runPieceCutouts(runId, file);
      return;
    }
  }

  // Fallback Path B: Single flat-lay / hanging garment (@imgly general cutout + recognize-item)
  itemScan.phase = 'bg';
  const cutoutRes = await trySmartSingleCutout(file, cutoutEngineMode.value, 'top', '', 24000);
  if (runId !== scanRunId) return;
  itemScan.uploadedCutoutDataUrl = cutoutRes.cutoutDataUrl;

  itemScan.phase = 'finalize';
  const fd = new FormData();
  fd.append('file', file);
  if (cutoutRes.cutoutFile) fd.append('cutoutFile', cutoutRes.cutoutFile);
  fd.append('sourceType', 'photo');
  const result = await visionApi.recognizeItem(fd);
  if (runId !== scanRunId) return;

  itemScan.usedRealLlm = result.usedRealLlm;
  itemScan.notice = result.usedRealLlm ? result.notice || '' : '单件已自动抠图（未配置模型 API Key，可手动微调名称）';
  itemScan.pieces = [
    makePiece({
      name: result.name,
      category: result.category,
      color: result.color,
      colorHex: result.colorHex || '#EFECE6',
      seasons: result.seasons?.length ? [...result.seasons] : ['春', '秋'],
      styleTags: result.styleTags?.length ? [...result.styleTags] : ['极简通勤'],
      material: result.material || '',
      svgType: result.svgType || 'tshirt',
      originalImageUrl: result.originalImageUrl,
      processedImageUrl: result.processedImageUrl,
      cropDataUrl: itemScan.uploadedOriginalDataUrl,
      cutoutStatus: cutoutRes.cutoutFile ? 'done' : 'failed',
      cutoutEngine: cutoutRes.engineUsed,
      aiRawResult: result.aiRawResult || '',
    }),
  ];
  itemScan.phase = 'done';
  itemScan.done = true;
  itemScan.viewMode = result.cutoutFallback || !cutoutRes.cutoutDataUrl ? 'original' : 'cutout';
}

async function scanSample(sample: ScanSample) {
  itemScan.phase = 'bg';
  await sleep(280);
  itemScan.phase = 'category';
  await sleep(280);
  itemScan.phase = 'finalize';
  await sleep(280);
  itemScan.usedRealLlm = false;
  itemScan.notice = sample.pieces.length > 1 ? `内置演示 · 已拆出 ${sample.pieces.length} 件单品` : '内置演示';
  itemScan.pieces = sample.pieces.map((p) =>
    makePiece({
      name: p.name,
      category: p.category,
      color: p.color,
      colorHex: p.colorHex,
      seasons: [...p.seasons],
      styleTags: [...p.styleTags],
      material: '精选混纺',
      price: p.price ?? '',
      svgType: p.svgType,
      processedImageUrl: `svg://${p.svgType}`,
      cutoutStatus: 'done',
    })
  );
  itemScan.phase = 'done';
  itemScan.done = true;
}

async function startItemScan() {
  if (itemScan.isScanning || !hasSource.value) return;
  itemScan.isScanning = true;
  itemScan.done = false;
  itemScan.pieces = [];
  itemScan.phase = 'bg';

  try {
    if (selectedFile.value) await scanUploadedPhoto(selectedFile.value);
    else if (itemScan.sample) await scanSample(itemScan.sample);
  } catch (e: any) {
    toast.show(e.message || '识别失败，请检查图片格式或 API Key 配置');
    itemScan.phase = 'idle';
  } finally {
    itemScan.isScanning = false;
  }
}

function toggleAll() {
  const next = !allSelected.value;
  selectablePieces.value.forEach((p) => {
    p.selected = next;
  });
}

function togglePieceSeason(p: ScanPiece, s: string) {
  const idx = p.seasons.indexOf(s);
  if (idx > -1) p.seasons.splice(idx, 1);
  else p.seasons.push(s);
  p.seasons.sort((a, b) => SEASON_OPTIONS.indexOf(a) - SEASON_OPTIONS.indexOf(b));
  p.corrected = true;
}

function removeTag(p: ScanPiece, index: number) {
  p.styleTags.splice(index, 1);
  p.corrected = true;
}

function addTag(p: ScanPiece, raw?: string) {
  const v = (raw ?? p.newTag).trim();
  if (v && !p.styleTags.includes(v)) {
    p.styleTags.push(v);
    p.corrected = true;
  }
  p.newTag = '';
}

function onTagEnter(e: KeyboardEvent, p: ScanPiece) {
  if (e.isComposing) return;
  addTag(p);
}

function tagSuggestions(p: ScanPiece) {
  return ALL_STYLE_OPTIONS.filter((t) => !p.styleTags.includes(t)).slice(0, 5);
}

function setPieceColor(p: ScanPiece, sw: (typeof COLOR_SWATCHES)[number]) {
  p.color = sw.name;
  p.colorHex = sw.hex;
  p.corrected = true;
}

function summaryLine(p: ScanPiece) {
  if (p.saved) return '已添加到衣橱';
  if (p.duplicateOf) return `衣橱已有相似「${p.duplicateOf}」，已默认不勾选`;
  const seasonText = p.seasons.map((s) => `${s}季`).join('、');
  const tags = p.styleTags.filter((t) => t !== '日常百搭').slice(0, 2);
  return [seasonText, ...tags].filter(Boolean).join(' · ') || '点击补充季节与风格';
}

async function saveSelectedPieces() {
  const targets = selectablePieces.value.filter((p) => p.selected);
  if (!targets.length || savingItem.value) return;
  savingItem.value = true;
  let ok = 0;
  let failed = 0;
  let lastError = '';
  let imageFallbacks = 0;
  for (const p of targets) {
    try {
      let originalUrl = p.originalImageUrl;
      let processedUrl = p.processedImageUrl;
      if (p.cropFile) {
        await waitForPieceCutout(p);
        processedUrl = `svg://${p.svgType}`;
        try {
          const fd = new FormData();
          fd.append('file', p.cropFile);
          if (p.cutoutFile) fd.append('cutoutFile', p.cutoutFile);
          const stored = await visionApi.storeItemImage(fd);
          originalUrl = stored.originalImageUrl;
          processedUrl = stored.processedImageUrl;
        } catch {
          imageFallbacks += 1;
        }
      }
      if (processedUrl.startsWith('data:')) processedUrl = `svg://${p.svgType}`;
      await wardrobeStore.createItem({
        name: p.name.trim() || '未命名衣物',
        category: p.category,
        color: p.color,
        colorHex: p.colorHex,
        seasons: p.seasons.length ? [...p.seasons] : ['春', '秋'],
        styleTags: p.styleTags.length ? [...p.styleTags] : ['日常百搭'],
        material: p.material || undefined,
        price: p.price !== '' && Number(p.price) >= 0 ? Number(p.price) : undefined,
        svgType: p.svgType,
        originalImageUrl: originalUrl || undefined,
        processedImageUrl: processedUrl || `svg://${p.svgType}`,
        sourceType: itemScan.sample?.sourceType || 'photo',
        isManuallyCorrected: p.corrected,
        aiRawResult: p.aiRawResult || undefined,
      });
      p.saved = true;
      p.selected = false;
      p.expanded = false;
      ok += 1;
    } catch (e: any) {
      failed += 1;
      lastError = e?.message || '入库失败';
    }
  }
  savingItem.value = false;
  if (ok > 0) {
    toast.show(
      `已添加 ${ok} 件到衣橱${failed ? `，${failed} 件失败` : ''}${imageFallbacks ? `（${imageFallbacks} 件图片上传失败，已用占位图）` : ''}`,
      '去衣橱查看',
      () => router.push('/wardrobe')
    );
  } else {
    toast.show(lastError || '入库失败');
  }
}

function toggleExpand(p: ScanPiece) {
  if (detailViewMode.value) {
    p.moreOpen = !p.moreOpen;
    return;
  }
  const next = !p.expanded;
  itemScan.pieces.forEach((x) => {
    x.expanded = false;
  });
  p.expanded = next;
}

function restartScan() {
  selectedFile.value = null;
  itemScan.sample = null;
  itemScan.uploadedOriginalDataUrl = null;
  resetScanResult();
}

// ==================== 模式 B：穿搭灵感街拍拆解 ====================
const inspFileInputRef = ref<HTMLInputElement | null>(null);
const inspFile = ref<File | null>(null);
const inspUploadedPreview = ref<string | null>(null);

const inspirationState = reactive({
  presetIdx: 0,
  activeHotspot: 0,
  query: INSPIRATION_PRESETS[0].defaultQuery,
  budget: Number(wardrobeStore.preference.budgetLimit) || 300,
  isAnalyzing: false,
  overallStyle: '韩系松弛通勤',
  notice: '',
  items: [] as InspirationItemMatch[],
});

const currentPreset = computed(() => INSPIRATION_PRESETS[inspirationState.presetIdx]);

function triggerInspUpload() {
  inspFileInputRef.value?.click();
}

function handleInspFileUpload(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  if (!file.type.startsWith('image/')) {
    toast.show('仅支持上传 JPG / PNG / WEBP 街拍图片');
    return;
  }
  inspFile.value = file;
  const reader = new FileReader();
  reader.onload = (ev) => {
    inspUploadedPreview.value = String(ev.target?.result || '');
  };
  reader.readAsDataURL(file);
  input.value = '';
  runInspirationAnalysis();
}

function selectInspirationPreset(idx: number) {
  inspFile.value = null;
  inspUploadedPreview.value = null;
  inspirationState.presetIdx = idx;
  inspirationState.activeHotspot = 0;
  inspirationState.query = INSPIRATION_PRESETS[idx].defaultQuery;
  runInspirationAnalysis();
}

async function runInspirationAnalysis() {
  inspirationState.isAnalyzing = true;
  try {
    const fd = new FormData();
    if (inspFile.value) {
      fd.append('file', inspFile.value);
    } else {
      fd.append('presetId', currentPreset.value.id);
    }
    fd.append('userQuery', inspirationState.query || '');
    fd.append('budgetLimit', String(inspirationState.budget || 300));
    const res = await visionApi.analyzeInspiration(fd);
    inspirationState.overallStyle = res.overallStyle;
    inspirationState.items = res.items || [];
    inspirationState.notice = res.notice || '';
    inspirationState.activeHotspot = 0;
  } catch (e: any) {
    toast.show(e.message || '街拍灵感拆解失败');
  } finally {
    inspirationState.isAnalyzing = false;
  }
}

let inspirationLoaded = false;
function ensureInspirationLoaded() {
  if (inspirationLoaded) return;
  inspirationLoaded = true;
  runInspirationAnalysis();
}

onMounted(() => {
  if (scanMode.value === 'inspiration') ensureInspirationLoaded();
});

watch(scanMode, (m) => {
  if (m === 'inspiration') ensureInspirationLoaded();
});

function sleep(ms: number) {
  return new Promise((r) => setTimeout(r, ms));
}
</script>

<template>
  <section class="space-y-5">
    <!-- 模式切换 -->
    <div class="flex justify-center">
      <div class="inline-flex p-1 rounded-full bg-[#F5F5F5]" role="tablist" aria-label="识别模式">
        <button
          role="tab"
          :aria-selected="scanMode === 'item'"
          @click="scanMode = 'item'"
          class="px-5 py-2 rounded-full text-[13px] font-medium transition"
          :class="scanMode === 'item' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
        >
          录入衣物
        </button>
        <button
          role="tab"
          :aria-selected="scanMode === 'inspiration'"
          @click="scanMode = 'inspiration'"
          class="px-5 py-2 rounded-full text-[13px] font-medium transition"
          :class="scanMode === 'inspiration' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
        >
          街拍找同款
        </button>
      </div>
    </div>

    <!-- ==================== 录入衣物：截图自动拆衣抠图分类 ==================== -->
    <div v-if="scanMode === 'item'" class="grid grid-cols-1 lg:grid-cols-12 gap-5 items-start">
      <!-- 左：原图/上传区 -->
      <div class="lg:col-span-5 bg-white rounded-3xl border border-black/[0.08] p-5 flex flex-col gap-4">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <div
            class="inline-flex p-0.5 rounded-full bg-[#F5F5F5] text-[11.5px]"
            role="group"
            aria-label="抠图引擎切换"
          >
            <button
              v-for="eng in CUTOUT_ENGINE_OPTIONS"
              :key="eng.key"
              type="button"
              :title="eng.hint"
              @click="switchCutoutEngine(eng.key)"
              class="px-2.5 py-1 rounded-full font-medium transition"
              :class="cutoutEngineMode === eng.key ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
            >
              {{ eng.label }}
            </button>
          </div>

          <div v-if="itemScan.done && itemScan.uploadedCutoutDataUrl" class="flex items-center gap-1.5">
            <div class="inline-flex p-0.5 rounded-full bg-[#F5F5F5] text-[11.5px]">
              <button
                type="button"
                @click="itemScan.viewMode = 'original'"
                class="px-2.5 py-1 rounded-full font-medium transition"
                :class="itemScan.viewMode === 'original' ? 'bg-black text-white' : 'text-[#666666]'"
              >
                原图
              </button>
              <button
                type="button"
                @click="itemScan.viewMode = 'cutout'"
                class="px-2.5 py-1 rounded-full font-medium transition"
                :class="itemScan.viewMode === 'cutout' ? 'bg-black text-white' : 'text-[#666666]'"
              >
                抠图
              </button>
            </div>
          </div>
        </div>

        <input ref="fileInputRef" type="file" accept="image/*" class="hidden" @change="handleFileUpload" />

        <div
          class="relative rounded-2xl overflow-hidden aspect-[4/3] lg:aspect-[4/4.6] flex items-center justify-center"
          :class="
            hasSource
              ? itemScan.done && itemScan.viewMode === 'cutout'
                ? 'cutout-checker'
                : 'bg-[#F5F5F5]'
              : 'bg-[#FAFAFA] border border-dashed border-black/[0.16]'
          "
        >
          <button
            v-if="!hasSource"
            type="button"
            @click="triggerFileUpload"
            class="w-full h-full flex flex-col items-center justify-center text-center p-6 hover:bg-[#F5F5F5] transition"
          >
            <span class="w-12 h-12 rounded-full bg-white border border-black/[0.08] flex items-center justify-center mb-3 text-black">
              <AppIcon name="upload" :size="20" />
            </span>
            <span class="text-[14.5px] font-semibold text-black">上传任意穿搭截图或衣物照片</span>
            <span class="text-[12px] text-[#666666] mt-1.5 max-w-[240px] leading-relaxed">
              自动去除背景与人像，把上衣、裤子、鞋子、包袋逐件抠出并分类
            </span>
            <span class="mt-4 px-5 py-2 rounded-full bg-black text-white text-[13px] font-medium">选择照片 / 截图</span>
          </button>

          <template v-else>
            <img
              v-if="itemScan.uploadedOriginalDataUrl"
              :src="
                itemScan.viewMode === 'cutout' && itemScan.uploadedCutoutDataUrl
                  ? itemScan.uploadedCutoutDataUrl
                  : itemScan.uploadedOriginalDataUrl
              "
              alt="已上传的照片"
              class="max-w-full max-h-full object-contain"
            />
            <div
              v-else-if="itemScan.sample"
              class="w-full px-6 flex items-center justify-center gap-3"
              :aria-label="`示例：${itemScan.sample.shortLabel}`"
            >
              <div
                v-for="p in itemScan.sample.pieces"
                :key="p.svgType"
                class="aspect-square"
                :class="itemScan.sample.pieces.length > 1 ? 'w-1/3' : 'w-3/5'"
              >
                <GarmentSvg :type="p.svgType" :color="p.colorHex" class="w-full h-full" />
              </div>
            </div>

            <!-- Scanning Overlay -->
            <div v-if="itemScan.isScanning" class="absolute inset-0 z-20 bg-white/35 backdrop-blur-[1px] overflow-hidden">
              <div class="scan-beam w-full h-16 bg-gradient-to-b from-transparent via-black/10 to-black/25 border-b-2 border-black" />
              <div
                class="absolute bottom-4 left-1/2 -translate-x-1/2 px-4 py-1.5 rounded-full bg-black text-white text-[12px] font-medium whitespace-nowrap inline-flex items-center gap-1.5 shadow-float"
                role="status"
                aria-live="polite"
              >
                <AppIcon :name="itemScan.phase === 'category' ? 'tag' : 'scissors'" :size="13" />
                {{ phaseBannerText }}
              </div>
            </div>
          </template>
        </div>

        <!-- 示例：仅在未上传照片时显示 -->
        <div v-if="!itemScan.uploadedOriginalDataUrl" class="space-y-2">
          <span class="block text-[12px] text-[#666666]">试试内置示例</span>
          <div class="flex flex-wrap gap-2">
            <button
              v-for="s in SAMPLES"
              :key="s.id"
              type="button"
              @click="selectScanSample(s)"
              :disabled="itemScan.isScanning"
              class="px-3.5 py-1.5 rounded-full border text-[12px] font-medium transition disabled:opacity-50"
              :class="
                itemScan.sample?.id === s.id
                  ? 'border-black bg-black text-white'
                  : 'border-black/[0.08] bg-white text-black hover:border-black/30'
              "
            >
              {{ s.shortLabel }}
            </button>
          </div>
        </div>

        <div v-if="hasSource && !itemScan.isScanning" class="flex items-center gap-2">
          <button
            type="button"
            @click="startItemScan"
            class="flex-1 py-2.5 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] text-black text-[13px] font-medium transition flex items-center justify-center gap-1.5"
          >
            <AppIcon name="refresh" :size="14" />
            重新识别
          </button>
          <button
            type="button"
            @click="triggerFileUpload"
            class="flex-1 py-2.5 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] text-black text-[13px] font-medium transition flex items-center justify-center gap-1.5"
          >
            <AppIcon name="upload" :size="14" />
            {{ itemScan.uploadedOriginalDataUrl ? '换一张截图' : '上传照片' }}
          </button>
        </div>
      </div>

      <!-- 右：Acloset 风格自动拆件抠图清单 -->
      <div class="lg:col-span-7 bg-white rounded-3xl border border-black/[0.08] p-5 md:p-6 flex flex-col min-h-[420px]">
        <!-- Top Header: Status or Result Count + Detail View Toggle -->
        <div class="flex items-center justify-between gap-3 pb-3 border-b border-black/[0.06]">
          <div class="flex items-center gap-2 min-w-0">
            <template v-if="itemScan.isScanning">
              <AppIcon
                :name="itemScan.phase === 'category' ? 'tag' : itemScan.phase === 'finalize' ? 'sparkles' : 'scissors'"
                :size="16"
                class="text-black shrink-0"
              />
              <h3 class="text-[15.5px] font-semibold text-black truncate">{{ phaseBannerText }}</h3>
            </template>
            <template v-else>
              <h3 class="text-[16px] font-semibold text-black">识别结果</h3>
              <span v-if="itemScan.done" class="font-mono text-[12px] text-[#666666]">
                共 {{ itemScan.pieces.length }} 件
              </span>
            </template>
          </div>

          <button
            v-if="itemScan.done && itemScan.pieces.length > 0"
            type="button"
            @click="detailViewMode = !detailViewMode"
            class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full border text-[12px] font-medium transition shrink-0"
            :class="
              detailViewMode
                ? 'bg-black text-white border-black'
                : 'bg-white text-black border-black/[0.12] hover:border-black'
            "
          >
            <AppIcon :name="detailViewMode ? 'list' : 'layout-grid'" :size="13" />
            {{ detailViewMode ? '简洁视图' : '详细视图' }}
          </button>
        </div>

        <!-- Select All / Delete Bar (Matches 2.mp4 00:19) -->
        <div
          v-if="itemScan.done && selectablePieces.length > 0"
          class="flex items-center justify-between py-2.5 text-[12.5px] border-b border-black/[0.04]"
        >
          <button
            type="button"
            @click="toggleAll"
            class="inline-flex items-center gap-2 text-black font-medium"
          >
            <span
              class="w-4.5 h-4.5 w-[18px] h-[18px] rounded-[5px] border flex items-center justify-center transition"
              :class="allSelected ? 'bg-black border-black text-white' : 'bg-white border-black/30'"
            >
              <AppIcon v-if="allSelected" name="check" :size="11" />
            </span>
            全选
          </button>

          <button
            type="button"
            @click="removeSelectedPieces"
            :disabled="selectedCount === 0"
            class="text-[#666666] hover:text-black disabled:opacity-35 transition"
          >
            删除
          </button>
        </div>

        <!-- Subtle Notice Line -->
        <p v-if="itemScan.done && itemScan.notice" class="mt-2.5 text-[12px] leading-relaxed text-[#666666]">
          {{ itemScan.notice }}
          <button
            v-if="!itemScan.usedRealLlm && itemScan.uploadedOriginalDataUrl"
            type="button"
            @click="router.push({ path: '/profile', query: { tab: 'settings', panel: 'ai' } })"
            class="ml-1 font-medium text-black underline underline-offset-4"
          >
            配置 API Key 升级精细命名
          </button>
        </p>

        <!-- Empty State -->
        <div
          v-if="!itemScan.done && !itemScan.isScanning && itemScan.pieces.length === 0"
          class="mt-4 flex-1 min-h-[260px] rounded-2xl bg-[#FAFAFA] border border-dashed border-black/[0.14] flex flex-col items-center justify-center text-center px-6"
        >
          <AppIcon name="scissors" :size="24" class="text-black/30 mb-2.5" />
          <p class="text-[13.5px] font-medium text-black">上传截图后，衣服 / 裤子 / 鞋子会自动拆出在这里</p>
          <p class="text-[12px] text-[#777777] mt-1">支持直接相册截屏、小红书穿搭图或单品平铺图</p>
        </div>

        <!-- Initial Skeleton (Before SegFormer finishes discovering pieces) -->
        <ul
          v-else-if="itemScan.isScanning && itemScan.pieces.length === 0"
          class="mt-4 space-y-4"
          aria-busy="true"
        >
          <li v-for="n in 3" :key="n" class="flex items-center gap-3.5 animate-pulse">
            <span class="w-5 h-5 rounded-md bg-[#EDEDED]" />
            <span class="relative w-16 h-16 rounded-2xl bg-[#EDEDED] overflow-hidden cutout-shimmer" />
            <span class="flex-1 space-y-2">
              <span class="block h-4 w-16 rounded-md bg-[#EDEDED]" />
              <span class="block h-3.5 w-2/5 rounded bg-[#EDEDED]" />
              <span class="block h-3 w-1/3 rounded bg-[#F5F5F5]" />
            </span>
          </li>
        </ul>

        <!-- Progressive Pieces Checklist (Shows Live Shimmer Wipe While Cutting Out, Then Clean Cutout + Magic Wand) -->
        <ul v-else class="mt-1 divide-y divide-black/[0.06]">
          <li v-for="p in itemScan.pieces" :key="p.uid" class="py-3.5" :class="p.saved ? 'opacity-55' : ''">
            <div class="flex items-center gap-3.5">
              <!-- Checkbox -->
              <button
                type="button"
                role="checkbox"
                :aria-checked="p.selected || p.saved"
                :aria-label="`选择 ${p.name}`"
                :disabled="p.saved || itemScan.isScanning"
                @click="p.selected = !p.selected"
                class="w-[20px] h-[20px] rounded-[6px] border flex items-center justify-center shrink-0 transition"
                :class="
                  p.saved
                    ? 'bg-[#F5F5F5] border-black/[0.08] text-[#666666]'
                    : p.selected
                      ? 'bg-black border-black text-white'
                      : 'bg-white border-black/25 hover:border-black'
                "
              >
                <AppIcon v-if="p.selected || p.saved" name="check" :size="12" />
              </button>

              <!-- Garment Cutout Thumbnail + Shimmer Wipe + Magic Wand Edit Trigger -->
              <div
                class="relative w-16 h-16 rounded-2xl bg-[#F5F5F5] ring-1 ring-inset ring-black/[0.05] p-1.5 shrink-0 overflow-hidden"
                :class="[
                  !p.selected && !p.saved && p.duplicateOf ? 'opacity-50' : '',
                  p.cutoutStatus === 'running' ? 'cutout-shimmer' : '',
                ]"
                :title="p.cutoutStatus === 'running' ? '正在移除背景…' : '点击右下角魔棒可精修抠图'"
              >
                <GarmentVisual :image-url="p.processedImageUrl" :svg-type="p.svgType" :color-hex="p.colorHex" :alt="p.name" />
                <button
                  v-if="!p.saved && p.cutoutStatus !== 'running' && !p.processedImageUrl.startsWith('svg://')"
                  type="button"
                  @click.stop="openMaskEditorForPiece(p)"
                  aria-label="精修抠图"
                  title="手动画笔精修抠图"
                  class="absolute bottom-1 right-1 w-6 h-6 rounded-full bg-black/85 hover:bg-black text-white flex items-center justify-center shadow-sm transition active:scale-95"
                >
                  <AppIcon name="wand" :size="11" />
                </button>
              </div>

              <!-- Metadata Summary -->
              <button
                type="button"
                class="min-w-0 flex-1 flex items-center gap-2 text-left"
                :class="!p.selected && !p.saved && p.duplicateOf ? 'opacity-60' : ''"
                :aria-expanded="detailViewMode || p.expanded"
                :disabled="p.saved || itemScan.isScanning"
                @click="toggleExpand(p)"
              >
                <span class="min-w-0 flex-1">
                  <template v-if="p.cutoutStatus === 'running'">
                    <span class="block h-4 w-16 rounded bg-[#EDEDED] animate-pulse" />
                    <span class="block h-3.5 w-28 rounded bg-[#F2F2F2] mt-2 animate-pulse" />
                  </template>
                  <template v-else>
                    <span class="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-[#F2F2F2] text-[#333333] text-[11px] font-medium">
                      <AppIcon :name="categoryIconName(p.category)" :size="11" />
                      {{ categoryLabel(p.category) }}
                    </span>
                    <span class="flex items-center gap-1.5 mt-1">
                      <span
                        class="w-2.5 h-2.5 rounded-full border border-black/15 shrink-0"
                        :style="{ backgroundColor: p.colorHex }"
                      />
                      <span class="text-[14.5px] font-semibold text-black truncate">{{ p.name }}</span>
                    </span>
                    <span class="block mt-0.5 text-[12px] text-[#777777] truncate">{{ summaryLine(p) }}</span>
                  </template>
                </span>
                <AppIcon
                  v-if="!p.saved && !itemScan.isScanning"
                  name="chevron-right"
                  :size="16"
                  class="text-[#999999] transition-transform shrink-0"
                  :class="detailViewMode || p.expanded ? 'rotate-90' : ''"
                />
              </button>
            </div>

            <!-- Expanded / Detail-View Editor -->
            <div v-if="(detailViewMode || p.expanded) && !p.saved && !itemScan.isScanning" class="mt-3.5 pl-8 space-y-3.5">
              <!-- Quick Category + Season Row -->
              <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <div class="text-[11.5px] text-[#666666] mb-1.5">分类</div>
                  <div class="flex flex-wrap gap-1.5">
                    <button
                      v-for="cat in CATEGORY_OPTIONS"
                      :key="cat.key"
                      type="button"
                      @click="
                        p.category = cat.key;
                        p.corrected = true;
                      "
                      class="px-2.5 py-1 rounded-full text-[11.5px] font-medium border transition"
                      :class="
                        p.category === cat.key
                          ? 'bg-black text-white border-black'
                          : 'bg-white text-[#666666] border-black/[0.08] hover:border-black/30'
                      "
                    >
                      {{ cat.label }}
                    </button>
                  </div>
                </div>

                <div>
                  <div class="text-[11.5px] text-[#666666] mb-1.5">季节</div>
                  <div class="flex flex-wrap gap-1.5">
                    <button
                      v-for="s in SEASON_OPTIONS"
                      :key="s"
                      type="button"
                      @click="togglePieceSeason(p, s)"
                      class="min-w-[38px] px-3 py-1 rounded-full text-[11.5px] font-medium border transition"
                      :class="
                        p.seasons.includes(s)
                          ? 'bg-black text-white border-black'
                          : 'bg-white text-[#666666] border-black/[0.08] hover:border-black/30'
                      "
                    >
                      {{ s }}
                    </button>
                  </div>
                </div>
              </div>

              <!-- Style Tags -->
              <div>
                <div class="text-[11.5px] text-[#666666] mb-1.5">风格标签</div>
                <div class="flex flex-wrap gap-1.5">
                  <span
                    v-for="(tag, i) in p.styleTags"
                    :key="tag"
                    class="inline-flex items-center gap-1 pl-2.5 pr-1.5 py-0.5 rounded-full bg-black text-white text-[11.5px]"
                  >
                    {{ tag }}
                    <button
                      type="button"
                      @click="removeTag(p, i)"
                      :aria-label="`移除标签 ${tag}`"
                      class="w-4 h-4 rounded-full flex items-center justify-center text-white/70 hover:text-white"
                    >
                      <AppIcon name="x" :size="10" />
                    </button>
                  </span>
                  <button
                    v-for="t in tagSuggestions(p)"
                    :key="t"
                    type="button"
                    @click="addTag(p, t)"
                    class="px-2.5 py-0.5 rounded-full border border-dashed border-black/25 text-[11.5px] text-[#666666] hover:border-black hover:text-black transition"
                  >
                    + {{ t }}
                  </button>
                  <input
                    v-model="p.newTag"
                    @keydown.enter="onTagEnter($event, p)"
                    type="text"
                    placeholder="+ 自定义"
                    class="w-20 px-2.5 py-0.5 rounded-full border border-dashed border-black/25 bg-white focus:border-black focus:border-solid outline-none text-[11.5px] text-black placeholder:text-[#777777]"
                  />
                </div>
              </div>

              <!-- More Metadata (Name, Color Swatches, Price) -->
              <div>
                <button
                  type="button"
                  @click="p.moreOpen = !p.moreOpen"
                  class="text-[11.5px] font-medium text-[#666666] hover:text-black inline-flex items-center gap-1"
                >
                  名称 · 颜色 · 购入价
                  <AppIcon name="chevron-right" :size="12" class="transition-transform" :class="p.moreOpen ? 'rotate-90' : ''" />
                </button>

                <div v-if="p.moreOpen" class="mt-2.5 space-y-3">
                  <div class="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
                    <div>
                      <label :for="`name-${p.uid}`" class="block text-[11.5px] text-[#666666] mb-1">名称</label>
                      <input
                        :id="`name-${p.uid}`"
                        v-model="p.name"
                        @input="p.corrected = true"
                        type="text"
                        class="w-full px-3 py-1.5 rounded-xl bg-white border border-black/[0.08] focus:border-black outline-none text-[13px] text-black"
                      />
                    </div>
                    <div>
                      <label :for="`price-${p.uid}`" class="block text-[11.5px] text-[#666666] mb-1">购入价 ¥（选填）</label>
                      <input
                        :id="`price-${p.uid}`"
                        v-model="p.price"
                        @input="p.corrected = true"
                        type="number"
                        min="0"
                        step="1"
                        placeholder="如 299"
                        class="w-full px-3 py-1.5 rounded-xl bg-white border border-black/[0.08] focus:border-black outline-none font-mono text-[13px] text-black"
                      />
                    </div>
                  </div>

                  <div>
                    <div class="flex items-center justify-between mb-1.5">
                      <span class="text-[11.5px] text-[#666666]">颜色</span>
                      <span class="text-[11.5px] font-medium text-black">{{ p.color }}</span>
                    </div>
                    <div class="flex flex-wrap gap-2">
                      <button
                        v-for="sw in COLOR_SWATCHES"
                        :key="sw.name"
                        type="button"
                        @click="setPieceColor(p, sw)"
                        :aria-label="`颜色 ${sw.name}`"
                        class="w-6 h-6 rounded-full border transition flex items-center justify-center"
                        :style="{ backgroundColor: sw.hex }"
                        :class="p.color === sw.name ? 'border-black ring-2 ring-black/20 scale-110' : 'border-black/15 hover:scale-105'"
                      >
                        <AppIcon v-if="p.color === sw.name" name="check" :size="11" :class="sw.light ? 'text-black' : 'text-white'" />
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </li>
        </ul>

        <!-- Finalizing Cloud Enrichment Indicator -->
        <div
          v-if="itemScan.isScanning && itemScan.phase === 'finalize'"
          class="mt-4 py-3 rounded-2xl bg-[#FAFAFA] flex items-center justify-center gap-2 text-[12px] text-[#666666]"
        >
          <span class="w-1.5 h-1.5 rounded-full bg-black animate-bounce" />
          <span class="w-1.5 h-1.5 rounded-full bg-black animate-bounce [animation-delay:120ms]" />
          <span class="w-1.5 h-1.5 rounded-full bg-black animate-bounce [animation-delay:240ms]" />
          <span class="ml-1">正在准备分析结果…</span>
        </div>

        <!-- Sticky Bottom Primary CTA -->
        <div v-if="itemScan.done" class="mt-auto pt-4 sticky bottom-20 lg:static bg-white">
          <div class="pt-3.5 border-t border-black/[0.06] flex items-center justify-between gap-3">
            <template v-if="allSaved">
              <button type="button" @click="restartScan" class="text-[13px] font-medium text-[#666666] hover:text-black">
                再识别一张
              </button>
              <button
                type="button"
                @click="router.push('/wardrobe')"
                class="min-w-[160px] px-8 py-3 rounded-full bg-black hover:bg-black/85 text-white text-[14px] font-medium transition"
              >
                去衣橱查看
              </button>
            </template>
            <template v-else>
              <span class="text-[12.5px] text-[#666666]">
                已选 <span class="font-mono text-black">{{ selectedCount }}</span> / {{ selectablePieces.length }}
              </span>
              <button
                type="button"
                @click="saveSelectedPieces"
                :disabled="selectedCount === 0 || savingItem"
                class="min-w-[170px] px-8 py-3 rounded-full bg-black hover:bg-black/85 disabled:bg-black/15 disabled:text-black/40 text-white text-[14px] font-medium transition"
              >
                {{ savingItem ? '正在添加…' : '添加到衣橱' }}
              </button>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- ==================== 街拍找同款 ==================== -->
    <div v-else class="grid grid-cols-1 lg:grid-cols-12 gap-6">
      <div class="lg:col-span-5 bg-white rounded-3xl border border-black/[0.08] p-5 md:p-6">
        <div class="flex items-center justify-between mb-4 gap-2">
          <h3 class="text-[16px] font-semibold text-black">街拍图</h3>
          <div class="flex items-center gap-1.5">
            <button
              v-for="(preset, idx) in INSPIRATION_PRESETS"
              :key="preset.id"
              @click="selectInspirationPreset(idx)"
              class="px-3 py-1 rounded-full text-[12px] font-medium border transition"
              :class="
                !inspUploadedPreview && inspirationState.presetIdx === idx
                  ? 'bg-black text-white border-black'
                  : 'bg-white text-[#666666] border-black/[0.08] hover:border-black/30'
              "
            >
              样本 {{ idx + 1 }}
            </button>
            <button
              @click="triggerInspUpload"
              class="px-3 py-1 rounded-full text-[12px] font-medium border border-black text-black hover:bg-[#F5F5F5] transition"
            >
              上传街拍
            </button>
            <input ref="inspFileInputRef" type="file" accept="image/*" class="hidden" @change="handleInspFileUpload" />
          </div>
        </div>

        <div class="relative rounded-2xl overflow-hidden border border-black/[0.08] aspect-[3/4] flatlay-canvas p-5 flex flex-col justify-between">
          <div class="flex items-center justify-between z-10">
            <span class="px-2.5 py-1 rounded-full bg-white/90 backdrop-blur-sm border border-black/[0.08] text-[11px] font-medium text-black">
              {{ inspUploadedPreview ? '自定义上传街拍' : currentPreset.sourceTag }}
            </span>
            <span class="text-[11px] text-[#666666] bg-white/80 px-2 py-0.5 rounded-md">点击编号查看拆解</span>
          </div>

          <div class="relative flex-1 my-2">
            <img
              v-if="inspUploadedPreview"
              :src="inspUploadedPreview"
              alt="上传街拍"
              class="w-full h-full object-contain rounded-xl"
            />
            <template v-else-if="inspirationState.items.length >= 4">
              <div
                class="absolute left-3 top-2 w-36 h-40 transition duration-300"
                :class="inspirationState.activeHotspot === 0 ? 'scale-105 drop-shadow-lg' : ''"
              >
                <GarmentSvg :type="inspirationState.items[0].svgType" :color="inspirationState.items[0].colorHex" class="w-full h-full" />
              </div>
              <div
                class="absolute right-4 top-6 w-28 h-28 transition duration-300"
                :class="inspirationState.activeHotspot === 1 ? 'scale-105 drop-shadow-lg' : ''"
              >
                <GarmentSvg :type="inspirationState.items[1].svgType" :color="inspirationState.items[1].colorHex" class="w-full h-full" />
              </div>
              <div
                class="absolute left-8 bottom-8 w-32 h-36 transition duration-300"
                :class="inspirationState.activeHotspot === 2 ? 'scale-105 drop-shadow-lg' : ''"
              >
                <GarmentSvg :type="inspirationState.items[2].svgType" :color="inspirationState.items[2].colorHex" class="w-full h-full" />
              </div>
              <div
                class="absolute right-4 bottom-4 w-28 h-28 transition duration-300"
                :class="inspirationState.activeHotspot === 3 ? 'scale-105 drop-shadow-lg' : ''"
              >
                <GarmentSvg :type="inspirationState.items[3].svgType" :color="inspirationState.items[3].colorHex" class="w-full h-full" />
              </div>
            </template>

            <button
              v-for="(item, idx) in inspirationState.items"
              :key="item.name + idx"
              @click="inspirationState.activeHotspot = idx"
              :aria-label="`查看拆解：${item.slotLabel} ${item.name}`"
              class="absolute z-20 -translate-x-1/2 -translate-y-1/2 group"
              :style="{ left: item.posX + '%', top: item.posY + '%' }"
            >
              <span class="hotspot-ring absolute inset-0 rounded-full bg-black/40" />
              <span
                class="relative flex items-center justify-center w-6 h-6 rounded-full text-[10px] font-mono font-semibold border-2 border-white shadow-md transition"
                :class="inspirationState.activeHotspot === idx ? 'bg-black text-white scale-110' : 'bg-white text-black'"
              >
                {{ idx + 1 }}
              </span>
              <span
                class="absolute left-7 top-1/2 -translate-y-1/2 whitespace-nowrap px-2 py-0.5 rounded-md bg-black text-white text-[10.5px] shadow-md pointer-events-none"
              >
                {{ item.slotLabel }}
              </span>
            </button>
          </div>

          <div class="z-10 bg-white/90 backdrop-blur-sm rounded-xl p-3 border border-black/[0.08]">
            <div class="font-semibold text-[14px] text-black">
              {{ inspUploadedPreview ? inspirationState.overallStyle : currentPreset.title }}
            </div>
            <div class="text-[11.5px] text-[#666666] mt-0.5">
              {{ inspUploadedPreview ? '多模态拆解 · 自动检索个人衣橱与平替' : currentPreset.subtitle }}
            </div>
          </div>

          <div v-if="inspirationState.isAnalyzing" class="absolute inset-0 z-30 bg-white/40 backdrop-blur-[1px]">
            <div class="scan-beam w-full h-20 bg-gradient-to-b from-transparent via-black/10 to-black/30 border-b-2 border-black" />
          </div>
        </div>
      </div>

      <div class="lg:col-span-7 space-y-5">
        <div class="bg-white rounded-3xl border border-black/[0.08] p-5 md:p-6">
          <h3 class="text-[16px] font-semibold text-black mb-3">拆解需求与预算</h3>
          <textarea
            v-model="inspirationState.query"
            rows="2"
            aria-label="拆解需求"
            class="w-full px-4 py-3 rounded-2xl bg-[#FAFAFA] border border-black/[0.08] focus:border-black focus:bg-white outline-none text-[13.5px] text-black resize-none transition"
            placeholder="输入自然语言需求..."
          />
          <div class="mt-4 flex flex-wrap items-center justify-between gap-4">
            <div class="flex items-center gap-3">
              <label for="insp-budget" class="text-[12.5px] text-[#666666]">单品预算上限</label>
              <div class="relative flex items-center">
                <span class="absolute left-3 font-mono text-[13px] text-[#666666]">¥</span>
                <input
                  id="insp-budget"
                  v-model.number="inspirationState.budget"
                  type="number"
                  min="50"
                  max="2000"
                  step="10"
                  class="w-28 pl-7 pr-3 py-1.5 rounded-xl bg-[#FAFAFA] border border-black/[0.08] font-mono text-[13.5px] font-semibold text-black outline-none focus:border-black"
                />
              </div>
            </div>
            <button
              @click="runInspirationAnalysis"
              :disabled="inspirationState.isAnalyzing"
              class="px-5 py-2.5 rounded-full bg-black hover:bg-black/85 disabled:bg-black/15 disabled:text-black/40 text-white text-[13.5px] font-medium transition flex items-center gap-2"
            >
              <AppIcon name="sparkles" :size="15" />
              {{ inspirationState.isAnalyzing ? '正在拆解…' : '拆解并检索' }}
            </button>
          </div>
        </div>

        <div class="bg-white rounded-3xl border border-black/[0.08] p-5 md:p-6">
          <div class="mb-4">
            <h3 class="text-[16px] font-semibold text-black">拆解与衣橱比对</h3>
            <p class="text-[12px] text-[#666666] mt-0.5">
              {{ inspirationState.notice || `已识别 ${inspirationState.items.length} 件单品 · 仅检索 @${authStore.user?.username} 的个人衣橱` }}
            </p>
          </div>

          <div class="space-y-3">
            <div
              v-for="(item, idx) in inspirationState.items"
              :key="item.name + idx"
              @click="inspirationState.activeHotspot = idx"
              class="p-4 rounded-2xl border transition cursor-pointer"
              :class="
                inspirationState.activeHotspot === idx
                  ? 'bg-[#FAFAFA] border-black'
                  : 'bg-white border-black/[0.08] hover:border-black/30'
              "
            >
              <div class="flex items-center gap-3">
                <div class="w-12 h-12 rounded-xl bg-[#F5F5F5] p-1.5 shrink-0">
                  <GarmentSvg :type="item.svgType" :color="item.colorHex" class="w-full h-full" />
                </div>
                <div class="min-w-0">
                  <div class="flex items-center gap-2 flex-wrap">
                    <span class="font-mono text-[11px] text-[#666666]">0{{ idx + 1 }} {{ item.slotLabel }}</span>
                    <h4 class="font-medium text-[14px] text-black">{{ item.name }}</h4>
                  </div>
                  <div v-if="item.matchedWardrobe" class="mt-1 flex items-center gap-2 text-[12px] flex-wrap">
                    <span class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-[#F5F5F5] text-black font-medium text-[11px]">
                      <AppIcon name="check" :size="11" />
                      衣橱已有 · <span class="font-mono">{{ item.similarity }}%</span> 相似
                    </span>
                    <span class="text-[#666666]">
                      可直接复用「<strong class="text-black font-medium">{{ item.matchedWardrobe.name }}</strong>」
                    </span>
                  </div>
                  <div v-else class="mt-1 flex items-center gap-2 text-[12px] flex-wrap">
                    <span class="inline-flex items-center px-2 py-0.5 rounded-full border border-black text-black font-medium text-[11px]">
                      衣橱缺失 · 建议补全
                    </span>
                    <span class="text-[#666666]">已匹配预算内相似平替</span>
                  </div>
                </div>
              </div>

              <div v-if="!item.matchedWardrobe" class="mt-3 pt-3 border-t border-black/[0.06]">
                <div v-if="item.products?.length" class="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
                  <div
                    v-for="prod in item.products"
                    :key="prod.id"
                    class="p-2.5 rounded-xl bg-white border border-black/[0.08] flex items-center gap-3 hover:border-black/30 transition"
                  >
                    <div class="w-11 h-11 rounded-lg bg-[#F5F5F5] p-1 shrink-0">
                      <GarmentVisual :image-url="prod.imageUrl" :svg-type="prod.svgType" :color-hex="prod.colorHex" :alt="prod.title" />
                    </div>
                    <div class="min-w-0 flex-1">
                      <div class="flex items-center gap-1.5">
                        <span class="text-[10px] px-1.5 py-0.5 rounded font-medium shrink-0" :class="platformBadgeClass(prod.platform)">
                          {{ prod.platformLabel }}
                        </span>
                        <span class="text-[12px] font-medium text-black truncate">{{ prod.title }}</span>
                      </div>
                      <div class="flex items-center justify-between mt-1">
                        <span class="font-mono font-semibold text-[13px] text-black">¥{{ prod.price }}</span>
                        <button
                          @click.stop="wardrobeStore.activeProductModal = prod"
                          class="text-[11px] font-medium text-black underline underline-offset-4"
                        >
                          去看看 ↗
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
                <div v-else class="text-[12px] text-[#666666] py-1">
                  当前预算 ¥{{ inspirationState.budget }} 以内暂无完全匹配商品。
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <CutoutMaskEditorModal
      :open="maskEditor.open"
      :title="maskEditor.title"
      :original-url="maskEditor.originalUrl"
      :cutout-url="maskEditor.cutoutUrl"
      @close="maskEditor.open = false"
      @apply="handleMaskEditorApply"
    />
  </section>
</template>
