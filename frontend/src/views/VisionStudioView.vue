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
  extractGarmentBySemanticMask,
  parseClothesSemanticMap,
  type ParsedClothesMap,
} from '@/utils/segformer';
import {
  ALL_STYLE_OPTIONS,
  CATEGORY_OPTIONS,
  COLOR_SWATCHES,
  INSPIRATION_PRESETS,
  SCAN_OUTFIT_SAMPLE,
  SCAN_SAMPLES,
  SCAN_STEPS,
  SEASON_OPTIONS,
  categoryLabel,
  platformBadgeClass,
} from '@/utils/constants';
import { cropImageRegion } from '@/utils/crop';
import type { BoundingBox, DetectedItem, InspirationItemMatch } from '@/types';
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

// ==================== 模式 A：AI 识别录入（一图多件，勾选后批量入库） ====================
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
  /** 多件识别：该单品从整图中裁出的实物图（入库时上传），null 表示无位置框 */
  cropFile: File | null;
  cropDataUrl: string | null;
  bbox: BoundingBox | null;
  /** 浏览器端抠图结果（可能失败，失败则用裁剪图入库） */
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
  bottom: 'trousers',
  shoes: 'sneakers',
  accessory: 'tote_bag',
};

const fileInputRef = ref<HTMLInputElement | null>(null);
const selectedFile = ref<File | null>(null);
const savingItem = ref(false);
const cutoutEngineMode = ref<CutoutEngineMode>('auto');

const CUTOUT_ENGINE_OPTIONS: Array<{ key: CutoutEngineMode; label: string; hint: string }> = [
  { key: 'auto', label: '自动', hint: '人像多件优先 SegFormer 拆衣，平铺单件走通用抠图' },
  { key: 'segformer', label: '人像拆衣', hint: '强制使用本地 SegFormer-B2-Clothes 18 类去人像拆衣' },
  { key: 'general', label: '单品抠图', hint: '强制使用通用前景抠图，适合平铺/挂拍单品' },
];

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
  currentStep: -1,
  done: false,
  viewMode: 'original' as 'original' | 'cutout',
  usedRealLlm: false,
  notice: '',
  pieces: [] as ScanPiece[],
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

// 每次重新识别都会递增，用于让上一轮仍在后台运行的抠图任务自行作废
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

/**
 * 双引擎多件抠图：
 * - auto / segformer：先跑一次全图 SegFormer-B2-Clothes（18 类去人像/皮肤/头发），按每件单品的品类与位置框精准提取像素级透明单品；
 * - 未命中语义掩码或用户选择 general 时，对裁剪图回退到通用前景抠图 (@imgly/background-removal)。
 */
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

  // Multi-item path: re-cut all unsaved pieces with the new engine
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

  // Single-item path: re-cut single garment in place
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
  itemScan.currentStep = -1;
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
    // 上传即自动识别，不再需要额外点一次「开始」
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
  if (/羽绒|棉服|大衣|毛衣|加绒|靴/.test(name)) return ['秋', '冬'];
  if (/短袖|t恤|背心|短裤|半裙|凉鞋|吊带/i.test(name)) return ['春', '夏'];
  if (category === 'accessory') return [...SEASON_OPTIONS];
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
    duplicateOf: d.similarWardrobeName ?? null,
    bbox: d.bbox ?? null,
  });
}

/** 按位置框把每件单品从整图裁出来；没有可用位置框的单品保留插画占位 */
async function attachCrops(file: File, detected: DetectedItem[], pieces: ScanPiece[]) {
  await Promise.all(
    detected.map(async (d, i) => {
      if (!d.bbox) return;
      try {
        const crop = await cropImageRegion(file, d.bbox, { name: `piece-${i + 1}` });
        pieces[i].cropFile = crop.file;
        pieces[i].cropDataUrl = crop.dataUrl;
        pieces[i].processedImageUrl = crop.dataUrl;
        pieces[i].cutoutStatus = 'pending';
      } catch {
        // 裁剪失败：该件保留插画占位，不影响其他单品
      }
    })
  );
}

async function scanUploadedPhoto(file: File) {
  await sleep(200);

  // 1) 优先尝试一图多件：服务端多件识别返回每件属性 + 位置框，前端逐件裁剪并抠出实物图；识别出 ≥2 件才进入多件清单
  const cfg = wardrobeStore.aiConfig;
  const llmReady = cfg.enabled && (cfg.hasApiKey || cfg.serverDefaultAvailable);
  if (llmReady) {
    itemScan.currentStep = 2;
    const runId = scanRunId;
    try {
      const fd = new FormData();
      fd.append('file', file);
      fd.append('sourceType', 'photo');
      const res = await visionApi.detectItems(fd);
      const detected = res.usedRealLlm ? res.items || [] : [];
      if (runId !== scanRunId) return;
      if (detected.length >= 2) {
        itemScan.currentStep = 3;
        const pieces = detected.map((d) => pieceFromDetected(d, res.originalImageUrl || ''));
        await attachCrops(file, detected, pieces);
        if (runId !== scanRunId) return;
        pieces.forEach((p) => {
          if (p.cutoutStatus === 'pending') registerCutoutWaiter(p.uid);
        });
        itemScan.usedRealLlm = true;
        itemScan.notice = `已自动识别出 ${detected.length} 件单品`;
        itemScan.pieces = pieces;
        itemScan.currentStep = 4;
        itemScan.done = true;
        void runPieceCutouts(runId);
        return;
      }
    } catch {
      // 拆分失败时回落到单件识别，不打断录入
    }
  }

  // 2) 单件识别：按当前引擎（自动 / 人像拆衣 / 单品抠图）浏览器端智能抠图 + 服务端多模态属性解析
  itemScan.currentStep = 1;
  const cutoutRes = await trySmartSingleCutout(file, cutoutEngineMode.value, 'top', '', 24000);
  itemScan.uploadedCutoutDataUrl = cutoutRes.cutoutDataUrl;

  itemScan.currentStep = 2;
  const fd = new FormData();
  fd.append('file', file);
  if (cutoutRes.cutoutFile) fd.append('cutoutFile', cutoutRes.cutoutFile);
  fd.append('sourceType', 'photo');
  const result = await visionApi.recognizeItem(fd);
  itemScan.currentStep = 3;
  await sleep(200);

  itemScan.usedRealLlm = result.usedRealLlm;
  itemScan.notice = result.usedRealLlm ? result.notice || '' : '未配置模型，仅做基础解析（1 件）';
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
  itemScan.currentStep = 4;
  itemScan.done = true;
  itemScan.viewMode = result.cutoutFallback || !cutoutRes.cutoutDataUrl ? 'original' : 'cutout';
}

async function scanSample(sample: ScanSample) {
  for (let step = 0; step < 4; step++) {
    itemScan.currentStep = step;
    await sleep(260);
  }
  itemScan.usedRealLlm = false;
  itemScan.notice = sample.pieces.length > 1 ? `内置演示 · 已拆分出 ${sample.pieces.length} 件` : '内置演示';
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
    })
  );
  itemScan.currentStep = 4;
  itemScan.done = true;
}

async function startItemScan() {
  if (itemScan.isScanning || !hasSource.value) return;
  itemScan.isScanning = true;
  itemScan.done = false;
  itemScan.pieces = [];
  itemScan.currentStep = 0;

  try {
    if (selectedFile.value) await scanUploadedPhoto(selectedFile.value);
    else if (itemScan.sample) await scanSample(itemScan.sample);
  } catch (e: any) {
    toast.show(e.message || '识别失败，请检查图片格式或 API Key 配置');
    itemScan.currentStep = -1;
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
  const parts = [p.seasons.join(' · '), ...p.styleTags.slice(0, 2)].filter(Boolean);
  return parts.join(' · ') || '点击补充季节与风格';
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
      // 多件识别的单品：等该件抠图完成，再把裁剪图 / 抠图上传到自己的上传目录，入库使用服务端返回的 URL
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
          imageFallbacks += 1; // 上传失败时仍可入库，退回整图 + 插画占位
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

// 仅在进入「街拍找同款」时才请求拆解，避免录入页每次打开都多发一次请求
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
  <section class="space-y-6">
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

    <!-- ==================== 录入衣物：一图多件 ==================== -->
    <div v-if="scanMode === 'item'" class="grid grid-cols-1 lg:grid-cols-12 gap-6">
      <!-- 左：照片 -->
      <div class="lg:col-span-5 bg-white rounded-3xl border border-black/[0.08] p-5 md:p-6 flex flex-col gap-4">
        <!-- 抠图引擎切换 + 单件原图/抠图切换 -->
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
            <div
              class="inline-flex p-0.5 rounded-full bg-[#F5F5F5] text-[11.5px]"
              role="group"
              aria-label="预览方式"
            >
              <button
                @click="itemScan.viewMode = 'original'"
                class="px-2.5 py-1 rounded-full font-medium transition"
                :class="itemScan.viewMode === 'original' ? 'bg-black text-white' : 'text-[#666666]'"
              >
                原图
              </button>
              <button
                @click="itemScan.viewMode = 'cutout'"
                class="px-2.5 py-1 rounded-full font-medium transition"
                :class="itemScan.viewMode === 'cutout' ? 'bg-black text-white' : 'text-[#666666]'"
              >
                抠图
              </button>
            </div>
            <button
              v-if="itemScan.pieces.length === 1 && !itemScan.pieces[0].saved"
              type="button"
              @click="openMaskEditorForPiece(itemScan.pieces[0])"
              class="px-2.5 py-1 rounded-full border border-black/15 hover:border-black text-[11.5px] font-medium text-black transition"
            >
              精修
            </button>
          </div>
        </div>

        <input ref="fileInputRef" type="file" accept="image/*" class="hidden" @change="handleFileUpload" />

        <div
          class="relative rounded-2xl overflow-hidden aspect-[4/3] lg:aspect-square flex items-center justify-center"
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
            <span class="text-[14px] font-medium text-black">上传衣物或整套穿搭照片</span>
            <span class="text-[12px] text-[#666666] mt-1">AI 自动识别并拆分上衣、裤子、鞋等单品</span>
            <span class="mt-4 px-5 py-2 rounded-full bg-black text-white text-[13px] font-medium">选择照片</span>
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

            <div v-if="itemScan.isScanning" class="absolute inset-0 z-20 bg-white/40 backdrop-blur-[1px] overflow-hidden">
              <div class="scan-beam w-full h-16 bg-gradient-to-b from-transparent via-black/10 to-black/30 border-b-2 border-black" />
              <div
                class="absolute bottom-4 left-1/2 -translate-x-1/2 px-4 py-1.5 rounded-full bg-black text-white text-[12px] font-mono whitespace-nowrap"
                role="status"
                aria-live="polite"
              >
                {{ SCAN_STEPS[itemScan.currentStep] || '处理中' }}…
              </div>
            </div>
          </template>
        </div>

        <!-- 示例：仅在还没上传真实照片时出现，点选即自动识别 -->
        <div v-if="!itemScan.uploadedOriginalDataUrl" class="space-y-2">
          <span class="block text-[12px] text-[#666666]">试试示例</span>
          <div class="flex flex-wrap gap-2">
            <button
              v-for="s in SAMPLES"
              :key="s.id"
              @click="selectScanSample(s)"
              :disabled="itemScan.isScanning"
              :aria-pressed="itemScan.sample?.id === s.id"
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

        <!-- 次级操作：主按钮只留给右侧「添加到衣橱」 -->
        <div v-if="hasSource && !itemScan.isScanning" class="flex items-center gap-2">
          <button
            @click="startItemScan"
            class="flex-1 py-2.5 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] text-black text-[13px] font-medium transition flex items-center justify-center gap-1.5"
          >
            <AppIcon name="refresh" :size="14" />
            重新识别
          </button>
          <button
            @click="triggerFileUpload"
            class="flex-1 py-2.5 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] text-black text-[13px] font-medium transition flex items-center justify-center gap-1.5"
          >
            <AppIcon name="upload" :size="14" />
            {{ itemScan.uploadedOriginalDataUrl ? '更换照片' : '上传照片' }}
          </button>
        </div>
      </div>

      <!-- 右：识别结果清单 -->
      <div class="lg:col-span-7 bg-white rounded-3xl border border-black/[0.08] p-5 md:p-6 flex flex-col">
        <div class="flex items-center justify-between gap-3">
          <div class="flex items-baseline gap-2">
            <h3 class="text-[16px] font-semibold text-black">识别结果</h3>
            <span v-if="itemScan.done" class="font-mono text-[12px] text-[#666666]">共 {{ itemScan.pieces.length }} 件</span>
          </div>
          <button
            v-if="itemScan.done && selectablePieces.length > 1"
            @click="toggleAll"
            class="text-[12.5px] font-medium text-black"
          >
            {{ allSelected ? '取消全选' : '全选' }}
          </button>
        </div>

        <p v-if="itemScan.done && itemScan.notice" class="mt-2 text-[12px] leading-relaxed text-[#666666]">
          {{ itemScan.notice }}
          <button
            v-if="!itemScan.usedRealLlm"
            @click="router.push({ path: '/profile', query: { tab: 'settings', panel: 'ai' } })"
            class="ml-1 font-medium text-black underline underline-offset-4"
          >
            配置 API Key 以识别真实照片
          </button>
        </p>

        <!-- 空态 -->
        <div
          v-if="!itemScan.done && !itemScan.isScanning"
          class="mt-4 flex-1 min-h-[260px] rounded-2xl bg-[#FAFAFA] border border-dashed border-black/[0.16] flex flex-col items-center justify-center text-center px-6"
        >
          <AppIcon name="shirt" :size="26" class="text-black/30 mb-2" />
          <p class="text-[13.5px] font-medium text-black">识别出的单品会出现在这里</p>
        </div>

        <!-- 识别中骨架 -->
        <ul v-else-if="itemScan.isScanning" class="mt-4 space-y-4 animate-pulse" aria-busy="true">
          <li v-for="n in 3" :key="n" class="flex items-center gap-3">
            <span class="w-6 h-6 rounded-full bg-[#EDEDED]" />
            <span class="w-14 h-14 rounded-xl bg-[#EDEDED]" />
            <span class="flex-1 space-y-2">
              <span class="block h-3.5 w-2/3 rounded bg-[#EDEDED]" />
              <span class="block h-3 w-1/2 rounded bg-[#F5F5F5]" />
            </span>
          </li>
        </ul>

        <!-- 清单 -->
        <ul v-else class="mt-2 divide-y divide-black/[0.06]">
          <li v-for="p in itemScan.pieces" :key="p.uid" class="py-3" :class="p.saved ? 'opacity-60' : ''">
            <div class="flex items-center gap-3">
              <button
                type="button"
                role="checkbox"
                :aria-checked="p.selected || p.saved"
                :aria-label="`选择 ${p.name}`"
                :disabled="p.saved"
                @click="p.selected = !p.selected"
                class="w-6 h-6 rounded-full border flex items-center justify-center shrink-0 transition"
                :class="
                  p.saved
                    ? 'bg-[#F5F5F5] border-black/[0.08] text-[#666666]'
                    : p.selected
                      ? 'bg-black border-black text-white'
                      : 'bg-white border-black/25 hover:border-black'
                "
              >
                <AppIcon v-if="p.selected || p.saved" name="check" :size="13" />
              </button>

              <div
                class="relative group w-14 h-14 rounded-xl bg-[#F5F5F5] ring-1 ring-inset ring-black/[0.04] p-1.5 shrink-0"
                :class="[
                  !p.selected && !p.saved && p.duplicateOf ? 'opacity-50' : '',
                  p.cutoutStatus === 'running' ? 'animate-pulse' : '',
                ]"
                :title="p.cutoutStatus === 'running' ? '正在抠图…' : '点击可手动画笔精修抠图'"
              >
                <GarmentVisual :image-url="p.processedImageUrl" :svg-type="p.svgType" :color-hex="p.colorHex" :alt="p.name" />
                <button
                  v-if="!p.saved && !p.processedImageUrl.startsWith('svg://')"
                  type="button"
                  @click.stop="openMaskEditorForPiece(p)"
                  class="absolute -bottom-1.5 -right-1.5 px-1.5 py-0.5 rounded-full bg-black text-white text-[9.5px] font-medium shadow-sm opacity-90 hover:opacity-100 transition"
                >
                  精修
                </button>
              </div>

              <button
                type="button"
                class="min-w-0 flex-1 flex items-center gap-2 text-left"
                :class="!p.selected && !p.saved && p.duplicateOf ? 'opacity-60' : ''"
                :aria-expanded="p.expanded"
                :aria-label="`${p.expanded ? '收起' : '编辑'} ${p.name}`"
                :disabled="p.saved"
                @click="toggleExpand(p)"
              >
                <span class="min-w-0 flex-1">
                  <span class="flex items-center gap-2">
                    <span class="text-[11px] px-2 py-0.5 rounded-full bg-[#F5F5F5] text-[#666666] shrink-0">
                      {{ categoryLabel(p.category) }}
                    </span>
                    <span class="text-[14px] font-medium text-black truncate">{{ p.name }}</span>
                  </span>
                  <span class="block mt-1 text-[12px] text-[#666666] truncate">{{ summaryLine(p) }}</span>
                </span>
                <AppIcon
                  v-if="!p.saved"
                  name="chevron-right"
                  :size="16"
                  class="text-[#666666] transition-transform"
                  :class="p.expanded ? 'rotate-90' : ''"
                />
              </button>
            </div>

            <!-- 展开：只露出季节 + 风格标签（名称 / 品类 / 颜色 / 价格收在「更多信息」） -->
            <div v-if="p.expanded && !p.saved" class="mt-3 space-y-4">
              <div>
                <div class="text-[12px] text-[#666666] mb-1.5">季节</div>
                <div class="flex flex-wrap gap-2">
                  <button
                    v-for="s in SEASON_OPTIONS"
                    :key="s"
                    @click="togglePieceSeason(p, s)"
                    :aria-pressed="p.seasons.includes(s)"
                    class="min-w-[44px] px-3.5 py-1.5 rounded-full text-[12.5px] font-medium border transition"
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

              <div>
                <div class="text-[12px] text-[#666666] mb-1.5">风格标签</div>
                <div class="flex flex-wrap gap-2">
                  <span
                    v-for="(tag, i) in p.styleTags"
                    :key="tag"
                    class="inline-flex items-center gap-1 pl-3 pr-1.5 py-1 rounded-full bg-black text-white text-[12px]"
                  >
                    {{ tag }}
                    <button
                      @click="removeTag(p, i)"
                      :aria-label="`移除标签 ${tag}`"
                      class="w-5 h-5 rounded-full flex items-center justify-center text-white/70 hover:text-white"
                    >
                      <AppIcon name="x" :size="12" />
                    </button>
                  </span>
                  <button
                    v-for="t in tagSuggestions(p)"
                    :key="t"
                    @click="addTag(p, t)"
                    class="px-3 py-1 rounded-full border border-dashed border-black/25 text-[12px] text-[#666666] hover:border-black hover:text-black transition"
                  >
                    + {{ t }}
                  </button>
                  <input
                    v-model="p.newTag"
                    @keydown.enter="onTagEnter($event, p)"
                    type="text"
                    placeholder="+ 自定义"
                    aria-label="自定义风格标签，回车添加"
                    class="w-24 px-3 py-1 rounded-full border border-dashed border-black/25 bg-white focus:border-black focus:border-solid outline-none text-[12px] text-black placeholder:text-[#666666] transition"
                  />
                </div>
              </div>

              <div>
                <button
                  type="button"
                  @click="p.moreOpen = !p.moreOpen"
                  :aria-expanded="p.moreOpen"
                  class="text-[12px] font-medium text-[#666666] hover:text-black inline-flex items-center gap-1"
                >
                  更多信息（名称 · 品类 · 颜色 · 购入价）
                  <AppIcon name="chevron-right" :size="13" class="transition-transform" :class="p.moreOpen ? 'rotate-90' : ''" />
                </button>

                <div v-if="p.moreOpen" class="mt-3 space-y-4">
                  <div>
                    <label :for="`name-${p.uid}`" class="block text-[12px] text-[#666666] mb-1.5">名称</label>
                    <input
                      :id="`name-${p.uid}`"
                      v-model="p.name"
                      @input="p.corrected = true"
                      type="text"
                      class="w-full px-3.5 py-2 rounded-xl bg-white border border-black/[0.08] focus:border-black outline-none text-[14px] text-black transition"
                    />
                  </div>

                  <div>
                    <div class="text-[12px] text-[#666666] mb-1.5">品类</div>
                    <div class="flex flex-wrap gap-2">
                      <button
                        v-for="cat in CATEGORY_OPTIONS"
                        :key="cat.key"
                        @click="
                          p.category = cat.key;
                          p.corrected = true;
                        "
                        :aria-pressed="p.category === cat.key"
                        class="px-3.5 py-1.5 rounded-full text-[12.5px] font-medium border transition"
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
                    <div class="flex items-center justify-between mb-1.5">
                      <span class="text-[12px] text-[#666666]">颜色</span>
                      <span class="text-[12px] font-medium text-black">{{ p.color }}</span>
                    </div>
                    <div class="flex flex-wrap gap-2.5">
                      <button
                        v-for="sw in COLOR_SWATCHES"
                        :key="sw.name"
                        @click="setPieceColor(p, sw)"
                        :aria-label="`颜色 ${sw.name}`"
                        :aria-pressed="p.color === sw.name"
                        class="w-7 h-7 rounded-full border transition flex items-center justify-center"
                        :style="{ backgroundColor: sw.hex }"
                        :class="p.color === sw.name ? 'border-black ring-2 ring-black/20 scale-110' : 'border-black/15 hover:scale-105'"
                      >
                        <AppIcon v-if="p.color === sw.name" name="check" :size="12" :class="sw.light ? 'text-black' : 'text-white'" />
                      </button>
                    </div>
                  </div>

                  <div>
                    <label :for="`price-${p.uid}`" class="block text-[12px] text-[#666666] mb-1.5">购入价 ¥（用于计算 CPW，选填）</label>
                    <input
                      :id="`price-${p.uid}`"
                      v-model="p.price"
                      @input="p.corrected = true"
                      type="number"
                      min="0"
                      step="1"
                      placeholder="如 299"
                      class="w-40 px-3.5 py-2 rounded-xl bg-white border border-black/[0.08] focus:border-black outline-none font-mono text-[14px] text-black transition"
                    />
                  </div>
                </div>
              </div>
            </div>
          </li>
        </ul>

        <!-- 底部操作 -->
        <div v-if="itemScan.done" class="mt-auto pt-5 sticky bottom-24 bg-white">
          <div class="pt-4 border-t border-black/[0.06] flex items-center justify-between gap-3">
            <template v-if="allSaved">
              <button @click="restartScan" class="text-[13px] font-medium text-[#666666] hover:text-black">再识别一张</button>
              <button
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
                @click="saveSelectedPieces"
                :disabled="selectedCount === 0 || savingItem"
                class="min-w-[160px] px-8 py-3 rounded-full bg-black hover:bg-black/85 disabled:bg-black/15 disabled:text-black/40 text-white text-[14px] font-medium transition"
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
      <!-- 左：街拍图 -->
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

      <!-- 右：拆解与比对 -->
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
