<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { visionApi } from '@/api/modules';
import { useAuthStore } from '@/stores/auth';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useToastStore } from '@/stores/toast';
import { tryBrowserCutout } from '@/utils/cutout';
import {
  CATEGORY_OPTIONS,
  COLOR_SWATCHES,
  INSPIRATION_PRESETS,
  SCAN_SAMPLES,
  SCAN_STEPS,
  SEASON_OPTIONS,
  platformBadgeClass,
} from '@/utils/constants';
import type { InspirationItemMatch } from '@/types';
import AppIcon from '@/components/AppIcon.vue';
import GarmentSvg from '@/components/GarmentSvg.vue';
import GarmentVisual from '@/components/GarmentVisual.vue';

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

// ==================== Mode A: Single Item Scan & Browser AI Cutout ====================
const fileInputRef = ref<HTMLInputElement | null>(null);
const selectedFile = ref<File | null>(null);
const savingItem = ref(false);

const itemScan = reactive({
  selectedSample: SCAN_SAMPLES[0] as (typeof SCAN_SAMPLES)[number] | null,
  uploadedOriginalDataUrl: null as string | null,
  uploadedCutoutDataUrl: null as string | null,
  originalImageUrl: '' as string,
  processedImageUrl: '' as string,
  cutoutFallback: false,
  isScanning: false,
  currentStep: -1,
  done: false,
  viewMode: 'cutout' as 'original' | 'cutout',
  isCorrected: false,
  confidence: 96.4,
  usedRealLlm: false,
  notice: '',
  aiRawResult: '',
  newTag: '',
  form: {
    name: '',
    category: 'outerwear',
    color: '焦糖棕',
    colorHex: '#8A5230',
    seasons: ['秋'] as string[],
    styleTags: [] as string[],
    material: '',
    price: '' as string | number,
    svgType: 'suede_jacket',
  },
});

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
  itemScan.selectedSample = null;
  itemScan.done = false;
  itemScan.currentStep = -1;
  itemScan.uploadedCutoutDataUrl = null;
  itemScan.isCorrected = false;
  itemScan.notice = '';
  const reader = new FileReader();
  reader.onload = (ev) => {
    itemScan.uploadedOriginalDataUrl = String(ev.target?.result || '');
    toast.show('已加载本地图片，点击「开始 AI 智能识别」执行抠图与多模态解析');
  };
  reader.readAsDataURL(file);
  input.value = '';
}

function selectScanSample(sample: (typeof SCAN_SAMPLES)[number]) {
  selectedFile.value = null;
  itemScan.selectedSample = sample;
  itemScan.uploadedOriginalDataUrl = null;
  itemScan.uploadedCutoutDataUrl = null;
  itemScan.done = false;
  itemScan.currentStep = -1;
  itemScan.isCorrected = false;
  itemScan.notice = '';
  itemScan.form.colorHex = sample.colorHex;
}

async function startItemScan() {
  if (itemScan.isScanning) return;
  itemScan.isScanning = true;
  itemScan.done = false;
  itemScan.isCorrected = false;
  itemScan.currentStep = 0;

  try {
    if (selectedFile.value) {
      // Real uploaded image pipeline: 1 Validate -> 2 Browser Cutout -> 3 Upload & VLM -> 4 Struct -> 5 Review
      await sleep(220);
      itemScan.currentStep = 1;
      const cutoutRes = await tryBrowserCutout(selectedFile.value, 16000);
      itemScan.uploadedCutoutDataUrl = cutoutRes.cutoutDataUrl;
      itemScan.cutoutFallback = cutoutRes.fallback;

      itemScan.currentStep = 2;
      const fd = new FormData();
      fd.append('file', selectedFile.value);
      if (cutoutRes.cutoutFile) {
        fd.append('cutoutFile', cutoutRes.cutoutFile);
      }
      fd.append('sourceType', 'photo');

      const result = await visionApi.recognizeItem(fd);
      itemScan.currentStep = 3;
      await sleep(220);

      itemScan.originalImageUrl = result.originalImageUrl;
      itemScan.processedImageUrl = result.processedImageUrl;
      itemScan.cutoutFallback = result.cutoutFallback;
      itemScan.confidence = result.confidence || 96.0;
      itemScan.usedRealLlm = result.usedRealLlm;
      itemScan.notice = result.notice || '';
      itemScan.aiRawResult = result.aiRawResult || '';
      itemScan.form = {
        name: result.name,
        category: result.category,
        color: result.color,
        colorHex: result.colorHex || '#EFECE6',
        seasons: result.seasons?.length ? [...result.seasons] : ['春', '秋'],
        styleTags: result.styleTags?.length ? [...result.styleTags] : ['极简通勤'],
        material: result.material || '',
        price: '',
        svgType: result.svgType || 'tshirt',
      };
      itemScan.currentStep = 4;
      itemScan.done = true;
      itemScan.viewMode = result.cutoutFallback ? 'original' : 'cutout';
      toast.show(result.usedRealLlm ? '多模态大模型解析完成，请复核属性' : '图像上传与基础解析完成，请复核或人工微调');
    } else if (itemScan.selectedSample) {
      // Demo sample 5-step guided preview
      for (let step = 0; step < 4; step++) {
        itemScan.currentStep = step;
        await sleep(320);
      }
      const src = itemScan.selectedSample;
      itemScan.originalImageUrl = '';
      itemScan.processedImageUrl = `svg://${src.svgType}`;
      itemScan.cutoutFallback = false;
      itemScan.confidence = 96.4;
      itemScan.usedRealLlm = false;
      itemScan.notice = '当前为内置测试样本演示；上传真实照片并配置 API Key 可启用多模态视觉实时解析';
      itemScan.form = {
        name: src.name,
        category: src.category,
        color: src.color,
        colorHex: src.colorHex,
        seasons: [...src.seasons],
        styleTags: [...src.styleTags],
        material: '精选混纺',
        price: src.category === 'outerwear' ? 329 : src.category === 'bottom' ? 259 : 169,
        svgType: src.svgType,
      };
      itemScan.currentStep = 4;
      itemScan.done = true;
      itemScan.viewMode = 'cutout';
      toast.show('5 步多模态解析与智能抠图完成，请复核或人工微调属性');
    }
  } catch (e: any) {
    toast.show(e.message || '识别失败，请检查图片格式或 API Key 配置');
    itemScan.currentStep = -1;
  } finally {
    itemScan.isScanning = false;
  }
}

function toggleScanSeason(s: string) {
  const idx = itemScan.form.seasons.indexOf(s);
  if (idx > -1) itemScan.form.seasons.splice(idx, 1);
  else itemScan.form.seasons.push(s);
  itemScan.isCorrected = true;
}

function addScanTag() {
  const v = itemScan.newTag.trim();
  if (v && !itemScan.form.styleTags.includes(v)) {
    itemScan.form.styleTags.push(v);
    itemScan.isCorrected = true;
  }
  itemScan.newTag = '';
}

async function saveScannedItem() {
  if (!itemScan.done) return;
  savingItem.value = true;
  try {
    const created = await wardrobeStore.createItem({
      name: itemScan.form.name || '未命名衣物',
      category: itemScan.form.category,
      color: itemScan.form.color,
      colorHex: itemScan.form.colorHex,
      seasons: itemScan.form.seasons.length ? [...itemScan.form.seasons] : ['秋'],
      styleTags: itemScan.form.styleTags.length ? [...itemScan.form.styleTags] : ['日常百搭'],
      material: itemScan.form.material || undefined,
      price: itemScan.form.price !== '' && Number(itemScan.form.price) >= 0 ? Number(itemScan.form.price) : undefined,
      svgType: itemScan.form.svgType,
      originalImageUrl: itemScan.originalImageUrl || undefined,
      processedImageUrl: itemScan.processedImageUrl || `svg://${itemScan.form.svgType}`,
      sourceType: itemScan.selectedSample?.sourceType || 'photo',
      isManuallyCorrected: itemScan.isCorrected,
      aiRawResult: itemScan.aiRawResult || undefined,
    });
    toast.show(
      `已成功存入「${created.name}」到 @${authStore.user?.username} 的衣橱`,
      '去衣橱查看',
      () => router.push('/wardrobe')
    );
  } catch (e: any) {
    toast.show(e.message || '入库失败');
  } finally {
    savingItem.value = false;
  }
}

// ==================== Mode B: Street Snap Inspiration Breakdown ====================
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

onMounted(() => {
  runInspirationAnalysis();
});

function sleep(ms: number) {
  return new Promise((r) => setTimeout(r, ms));
}
</script>

<template>
  <section class="space-y-6">
    <!-- Mode Segmented Switch -->
    <div class="flex justify-center">
      <div class="inline-flex p-1.5 rounded-2xl bg-oat-200/80 border border-oat-300">
        <button
          @click="scanMode = 'item'"
          class="px-5 py-2 rounded-xl text-[13.5px] font-medium transition flex items-center gap-2"
          :class="scanMode === 'item' ? 'bg-white text-charcoal shadow-sm' : 'text-warmgray hover:text-charcoal'"
        >
          <AppIcon name="camera" :size="15" />
          模式 A：单品入库 & 智能抠图
        </button>
        <button
          @click="scanMode = 'inspiration'"
          class="px-5 py-2 rounded-xl text-[13.5px] font-medium transition flex items-center gap-2"
          :class="scanMode === 'inspiration' ? 'bg-white text-charcoal shadow-sm' : 'text-warmgray hover:text-charcoal'"
        >
          <AppIcon name="scan" :size="15" />
          模式 B：穿搭灵感拆解 & 找同款
        </button>
      </div>
    </div>

    <!-- ==================== 模式 A：单品入库 & 5 步复核 ==================== -->
    <div v-if="scanMode === 'item'" class="space-y-5">
      <!-- 5-Step Progress Bar -->
      <div class="bg-white rounded-2xl border border-oat-300 px-5 py-4 shadow-card">
        <div class="grid grid-cols-5 gap-2">
          <div v-for="(step, idx) in SCAN_STEPS" :key="idx" class="flex flex-col sm:flex-row items-center gap-2">
            <div
              class="w-7 h-7 rounded-full flex items-center justify-center font-mono text-[12px] font-semibold shrink-0 transition-all duration-300"
              :class="
                itemScan.currentStep > idx
                  ? 'bg-emerald-600 text-white'
                  : itemScan.currentStep === idx && itemScan.isScanning
                    ? 'bg-terracotta text-white ring-4 ring-terracotta-light'
                    : itemScan.currentStep === idx && itemScan.done
                      ? 'bg-charcoal text-white'
                      : 'bg-oat-200 text-warmgray'
              "
            >
              <AppIcon v-if="itemScan.currentStep > idx" name="check" :size="14" />
              <span v-else>{{ idx + 1 }}</span>
            </div>
            <span
              class="text-[11px] sm:text-[12.5px] text-center sm:text-left font-medium transition"
              :class="itemScan.currentStep >= idx ? 'text-charcoal' : 'text-warmgray'"
            >
              {{ step }}
            </span>
          </div>
        </div>
      </div>

      <div class="grid grid-cols-1 lg:grid-cols-12 gap-6">
        <!-- Left Upload & Cutout Studio -->
        <div class="lg:col-span-6 bg-white rounded-3xl border border-oat-300 shadow-editorial p-6 flex flex-col justify-between">
          <div>
            <div class="flex items-center justify-between mb-4">
              <h3 class="font-serif text-[17px] font-semibold text-charcoal">图像采集与去背景工作台</h3>
              <div v-if="itemScan.done" class="inline-flex p-1 rounded-xl bg-oat-100 border border-oat-300 text-[12px]">
                <button
                  @click="itemScan.viewMode = 'original'"
                  class="px-3 py-1 rounded-lg font-medium transition"
                  :class="itemScan.viewMode === 'original' ? 'bg-white text-charcoal shadow-sm' : 'text-warmgray'"
                >
                  原图对比
                </button>
                <button
                  @click="itemScan.viewMode = 'cutout'"
                  class="px-3 py-1 rounded-lg font-medium transition"
                  :class="itemScan.viewMode === 'cutout' ? 'bg-charcoal text-white shadow-sm' : 'text-warmgray'"
                >
                  纯净抠图
                </button>
              </div>
            </div>

            <div
              class="relative rounded-2xl border-2 border-dashed overflow-hidden aspect-[4/3] flex items-center justify-center transition"
              :class="
                itemScan.selectedSample || itemScan.uploadedOriginalDataUrl
                  ? itemScan.done && itemScan.viewMode === 'cutout'
                    ? 'border-oat-300 cutout-checker'
                    : 'border-oat-300 bg-oat-100'
                  : 'border-oat-400 bg-oat-50 hover:bg-oat-100 cursor-pointer'
              "
              @click="!itemScan.selectedSample && !itemScan.uploadedOriginalDataUrl && triggerFileUpload()"
            >
              <input ref="fileInputRef" type="file" accept="image/*" class="hidden" @change="handleFileUpload" />

              <div v-if="!itemScan.selectedSample && !itemScan.uploadedOriginalDataUrl" class="text-center p-6">
                <div
                  class="w-14 h-14 rounded-2xl bg-white border border-oat-300 shadow-sm flex items-center justify-center mx-auto mb-3 text-charcoal"
                >
                  <AppIcon name="upload" :size="24" />
                </div>
                <p class="font-medium text-charcoal text-[14px]">点击上传衣物实物照或电商订单截图</p>
                <p class="text-[12px] text-warmgray mt-1">支持 JPG / PNG / WEBP（≤10MB），自动魔数校验与本地 AI 抠图</p>
              </div>

              <div v-else class="w-full h-full relative flex items-center justify-center">
                <!-- Background simulation for preset samples in original mode -->
                <div
                  v-if="itemScan.selectedSample && (!itemScan.done || itemScan.viewMode === 'original')"
                  class="absolute inset-0 pointer-events-none transition-opacity duration-500"
                >
                  <div
                    v-if="itemScan.selectedSample.sourceType === 'screenshot'"
                    class="w-full h-full bg-[#F6F6F6] p-4 flex flex-col justify-between"
                  >
                    <div class="flex items-center justify-between text-[11px] text-neutral-400 border-b border-neutral-200 pb-2">
                      <span class="font-semibold text-orange-500">淘宝订单详情 · 已签收</span>
                      <span class="font-mono">订单号 202611229081</span>
                    </div>
                    <div
                      class="bg-white/80 rounded-xl p-2.5 border border-neutral-200 flex items-center justify-between text-[11px] text-neutral-600 z-10"
                    >
                      <span>法式复古重磅条纹海魂衫长袖 · 藏蓝米白 L码</span>
                      <span class="font-mono font-semibold text-orange-600">¥129.00</span>
                    </div>
                  </div>
                  <div
                    v-else
                    class="w-full h-full relative overflow-hidden"
                    style="background: linear-gradient(160deg, #d9cfc1 0%, #c5b8a5 55%, #b5a48f 100%)"
                  >
                    <svg viewBox="0 0 400 300" class="w-full h-full opacity-55" preserveAspectRatio="none">
                      <rect x="0" y="0" width="400" height="190" fill="#E6DFD5" />
                      <line x1="0" y1="190" x2="400" y2="190" stroke="#A69682" stroke-width="2" />
                      <path d="M0,190 L400,190 L400,300 L0,300 Z" fill="#C4B097" />
                      <rect x="24" y="32" width="76" height="115" rx="6" fill="#D2C7B8" stroke="#BEB1A0" />
                      <circle cx="348" cy="64" r="24" fill="#DDD4C7" />
                    </svg>
                    <span class="absolute top-3 left-3 text-[10.5px] bg-black/50 text-white px-2.5 py-1 rounded-full backdrop-blur-sm">
                      原始杂乱背景照
                    </span>
                  </div>
                </div>

                <span
                  v-if="itemScan.done && itemScan.viewMode === 'cutout'"
                  class="absolute top-3 left-3 text-[10.5px] bg-emerald-700 text-white px-2.5 py-1 rounded-full z-10 flex items-center gap-1 shadow-sm"
                >
                  <AppIcon name="check" :size="12" />
                  {{ itemScan.cutoutFallback ? '已保留原图（降级）' : '已智能抠图 · 透明底平铺图' }}
                </span>

                <div
                  class="relative z-10 w-56 h-56 flex items-center justify-center transition-all duration-500"
                  :class="itemScan.done && itemScan.viewMode === 'cutout' ? 'drop-shadow-xl scale-105' : ''"
                >
                  <img
                    v-if="itemScan.uploadedOriginalDataUrl"
                    :src="
                      itemScan.viewMode === 'cutout' && itemScan.uploadedCutoutDataUrl
                        ? itemScan.uploadedCutoutDataUrl
                        : itemScan.uploadedOriginalDataUrl
                    "
                    alt="已上传衣物预览"
                    class="max-w-full max-h-full object-contain rounded-xl"
                  />
                  <GarmentSvg
                    v-else-if="itemScan.selectedSample"
                    :type="itemScan.selectedSample.svgType"
                    :color="itemScan.form.colorHex || itemScan.selectedSample.colorHex"
                    class="w-full h-full"
                  />
                </div>

                <!-- Scan Laser Overlay -->
                <div v-if="itemScan.isScanning" class="absolute inset-0 z-20 bg-charcoal/15 backdrop-blur-[1px] overflow-hidden">
                  <div
                    class="scan-beam w-full h-16 bg-gradient-to-b from-transparent via-terracotta/40 to-terracotta/70 border-b-2 border-terracotta"
                  />
                  <div
                    class="absolute bottom-4 left-1/2 -translate-x-1/2 px-4 py-1.5 rounded-full bg-charcoal/90 text-white text-[12px] font-mono flex items-center gap-2 shadow-lg whitespace-nowrap"
                  >
                    <span class="w-2 h-2 rounded-full bg-terracotta animate-ping" />
                    {{ SCAN_STEPS[itemScan.currentStep] || '处理中' }}...
                  </div>
                </div>
              </div>
            </div>

            <!-- Preset Sample Selector + Local File Trigger -->
            <div class="mt-4">
              <div class="flex items-center justify-between mb-2">
                <span class="text-[12px] font-medium text-warmgray">一键加载测试样本（或上传真实本地图片）：</span>
                <button @click="triggerFileUpload" class="text-[12px] text-terracotta hover:underline font-medium">
                  选择本地文件
                </button>
              </div>
              <div class="grid grid-cols-1 sm:grid-cols-3 gap-2">
                <button
                  v-for="sample in SCAN_SAMPLES"
                  :key="sample.id"
                  @click="selectScanSample(sample)"
                  class="text-left p-2.5 rounded-xl border text-[12px] transition flex items-center gap-2.5"
                  :class="
                    itemScan.selectedSample?.id === sample.id
                      ? 'bg-terracotta-light/60 border-terracotta text-charcoal font-medium'
                      : 'bg-oat-50 border-oat-300 hover:bg-oat-100 text-charcoal-soft'
                  "
                >
                  <div class="w-8 h-8 rounded-lg bg-white border border-oat-200 p-1 shrink-0">
                    <GarmentSvg :type="sample.svgType" :color="sample.colorHex" class="w-full h-full" />
                  </div>
                  <div class="min-w-0">
                    <div class="truncate font-medium text-[11.5px]">{{ sample.shortLabel }}</div>
                    <div class="text-[10px] text-warmgray">{{ sample.sourceLabel }}</div>
                  </div>
                </button>
              </div>
            </div>
          </div>

          <div class="mt-5 pt-4 border-t border-oat-200">
            <button
              @click="startItemScan"
              :disabled="(!itemScan.selectedSample && !itemScan.uploadedOriginalDataUrl) || itemScan.isScanning"
              class="w-full py-3 rounded-2xl bg-charcoal hover:bg-charcoal-hover disabled:bg-oat-300 disabled:text-warmgray text-white text-[14px] font-medium shadow-sm transition flex items-center justify-center gap-2"
            >
              <AppIcon name="sparkles" :size="16" />
              {{
                itemScan.isScanning
                  ? '多模态解析与抠图中...'
                  : itemScan.done
                    ? '重新执行 AI 智能识别'
                    : '开始 AI 智能识别'
              }}
            </button>
          </div>
        </div>

        <!-- Right Attribute Review & Manual Correction Form -->
        <div class="lg:col-span-6 bg-white rounded-3xl border border-oat-300 shadow-editorial p-6 flex flex-col justify-between">
          <div>
            <div class="flex items-center justify-between mb-4">
              <div>
                <h3 class="font-serif text-[17px] font-semibold text-charcoal">AI 属性解析与人工复核</h3>
                <p class="text-[12px] text-warmgray mt-0.5">
                  支持一键点选胶囊纠偏，存入当前账号（@{{ authStore.user?.username }}）
                </p>
              </div>
              <span
                v-if="itemScan.isCorrected"
                class="text-[11px] font-medium px-2.5 py-1 rounded-full bg-amber-100 text-amber-800 border border-amber-200"
              >
                已人工修正
              </span>
              <span
                v-else-if="itemScan.done"
                class="text-[11px] font-mono px-2.5 py-1 rounded-full bg-emerald-50 text-emerald-700 border border-emerald-200"
              >
                置信度 {{ itemScan.confidence }}%
              </span>
            </div>

            <div
              v-if="itemScan.notice && itemScan.done"
              class="mb-4 px-3.5 py-2.5 rounded-2xl bg-oat-50 border border-oat-200 text-[12px] text-charcoal-soft flex items-center justify-between gap-2"
            >
              <span>{{ itemScan.notice }}</span>
              <button
                v-if="!itemScan.usedRealLlm"
                @click="router.push('/profile')"
                class="text-terracotta font-medium shrink-0 hover:underline"
              >
                配置 API Key →
              </button>
            </div>

            <div
              v-if="!itemScan.done && !itemScan.isScanning"
              class="py-16 text-center rounded-2xl bg-oat-50 border border-dashed border-oat-300"
            >
              <AppIcon name="sliders" :size="28" class="mx-auto text-oat-400 mb-2" />
              <p class="text-[13px] text-warmgray">请先在左侧选择测试样本或上传真实图片，</p>
              <p class="text-[13px] text-warmgray">点击「开始 AI 智能识别」后将在此回显结构化属性。</p>
            </div>

            <div v-else-if="itemScan.isScanning" class="space-y-4 py-4 animate-pulse">
              <div class="h-10 bg-oat-200 rounded-xl w-3/4" />
              <div class="flex gap-2">
                <div v-for="n in 5" :key="n" class="h-8 w-16 bg-oat-200 rounded-full" />
              </div>
              <div class="h-12 bg-oat-200 rounded-xl w-full" />
              <div class="h-20 bg-oat-200 rounded-xl w-full" />
            </div>

            <div v-else class="space-y-4">
              <div class="grid grid-cols-12 gap-3">
                <div class="col-span-8">
                  <label class="block text-[12px] font-medium text-warmgray mb-1.5">单品名称</label>
                  <input
                    v-model="itemScan.form.name"
                    @input="itemScan.isCorrected = true"
                    type="text"
                    class="w-full px-3.5 py-2.5 rounded-xl bg-oat-50 border border-oat-300 focus:border-charcoal focus:bg-white outline-none text-[14px] font-medium text-charcoal transition"
                  />
                </div>
                <div class="col-span-4">
                  <label class="block text-[12px] font-medium text-warmgray mb-1.5">购入价 ¥（CPW）</label>
                  <input
                    v-model="itemScan.form.price"
                    @input="itemScan.isCorrected = true"
                    type="number"
                    min="0"
                    step="1"
                    placeholder="选填"
                    class="w-full px-3 py-2.5 rounded-xl bg-oat-50 border border-oat-300 focus:border-charcoal focus:bg-white outline-none font-mono text-[14px] font-medium text-charcoal transition"
                  />
                </div>
              </div>

              <div>
                <label class="block text-[12px] font-medium text-warmgray mb-1.5">一级品类（点击修正）</label>
                <div class="flex flex-wrap gap-2">
                  <button
                    v-for="cat in CATEGORY_OPTIONS"
                    :key="cat.key"
                    @click="itemScan.form.category = cat.key; itemScan.isCorrected = true"
                    class="px-3.5 py-1.5 rounded-full text-[12.5px] font-medium border transition"
                    :class="
                      itemScan.form.category === cat.key
                        ? 'bg-charcoal text-white border-charcoal'
                        : 'bg-oat-50 text-charcoal-soft border-oat-300 hover:bg-oat-200'
                    "
                  >
                    {{ cat.label }}
                  </button>
                </div>
              </div>

              <div>
                <div class="flex items-center justify-between mb-1.5">
                  <label class="text-[12px] font-medium text-warmgray">主色调色盘</label>
                  <span class="text-[12px] font-medium text-charcoal">{{ itemScan.form.color }}</span>
                </div>
                <div class="flex flex-wrap gap-2.5">
                  <button
                    v-for="sw in COLOR_SWATCHES"
                    :key="sw.name"
                    @click="
                      itemScan.form.color = sw.name;
                      itemScan.form.colorHex = sw.hex;
                      itemScan.isCorrected = true;
                    "
                    class="w-7 h-7 rounded-full border-2 transition flex items-center justify-center shadow-sm"
                    :style="{ backgroundColor: sw.hex }"
                    :class="
                      itemScan.form.color === sw.name
                        ? 'border-terracotta scale-110 ring-2 ring-terracotta-light'
                        : 'border-white hover:scale-105'
                    "
                    :title="sw.name"
                  >
                    <AppIcon
                      v-if="itemScan.form.color === sw.name"
                      name="check"
                      :size="12"
                      :class="sw.light ? 'text-charcoal' : 'text-white'"
                    />
                  </button>
                </div>
              </div>

              <div>
                <label class="block text-[12px] font-medium text-warmgray mb-1.5">适用季节（可多选）</label>
                <div class="flex flex-wrap gap-2">
                  <button
                    v-for="s in SEASON_OPTIONS"
                    :key="s"
                    @click="toggleScanSeason(s)"
                    class="px-3.5 py-1.5 rounded-full text-[12px] font-medium border transition"
                    :class="
                      itemScan.form.seasons.includes(s)
                        ? 'bg-terracotta-light text-terracotta-dark border-terracotta/50'
                        : 'bg-oat-50 text-warmgray border-oat-300 hover:bg-oat-100'
                    "
                  >
                    {{ s }}
                  </button>
                </div>
              </div>

              <div>
                <label class="block text-[12px] font-medium text-warmgray mb-1.5">风格语义标签</label>
                <div class="flex flex-wrap gap-1.5 mb-2">
                  <span
                    v-for="(tag, i) in itemScan.form.styleTags"
                    :key="tag"
                    class="inline-flex items-center gap-1 px-3 py-1 rounded-full bg-oat-100 border border-oat-300 text-[12px] text-charcoal"
                  >
                    {{ tag }}
                    <button
                      @click="
                        itemScan.form.styleTags.splice(i, 1);
                        itemScan.isCorrected = true;
                      "
                      class="text-warmgray hover:text-terracotta"
                    >
                      ×
                    </button>
                  </span>
                </div>
                <div class="flex gap-2">
                  <input
                    v-model="itemScan.newTag"
                    @keyup.enter="addScanTag"
                    type="text"
                    placeholder="输入自定义标签后按回车，如：法式复古"
                    class="flex-1 px-3 py-1.5 rounded-xl bg-oat-50 border border-oat-300 text-[12.5px] outline-none focus:border-charcoal"
                  />
                  <button
                    @click="addScanTag"
                    class="px-3 py-1.5 rounded-xl bg-oat-200 hover:bg-oat-300 text-[12px] font-medium text-charcoal"
                  >
                    添加
                  </button>
                </div>
              </div>
            </div>
          </div>

          <div class="mt-6 pt-4 border-t border-oat-200 flex items-center justify-between gap-3">
            <span class="text-[11.5px] text-warmgray font-mono">user_id={{ authStore.user?.id }}</span>
            <button
              @click="saveScannedItem"
              :disabled="!itemScan.done || savingItem"
              class="px-6 py-2.5 rounded-full bg-terracotta hover:bg-terracotta-dark disabled:bg-oat-300 disabled:text-warmgray text-white text-[13.5px] font-medium shadow-sm transition flex items-center gap-2"
            >
              <AppIcon name="check" :size="16" />
              {{ savingItem ? '正在写入衣橱...' : '确认存入我的衣橱' }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- ==================== 模式 B：穿搭灵感街拍拆解 ==================== -->
    <div v-else class="grid grid-cols-1 lg:grid-cols-12 gap-6">
      <!-- Left Street Snap Canvas -->
      <div class="lg:col-span-5 bg-white rounded-3xl border border-oat-300 shadow-editorial p-6">
        <div class="flex items-center justify-between mb-4 gap-2">
          <h3 class="font-serif text-[17px] font-semibold text-charcoal">穿搭灵感街拍图</h3>
          <div class="flex items-center gap-1.5">
            <button
              v-for="(preset, idx) in INSPIRATION_PRESETS"
              :key="preset.id"
              @click="selectInspirationPreset(idx)"
              class="px-2.5 py-1 rounded-lg text-[11.5px] font-medium border transition"
              :class="
                !inspUploadedPreview && inspirationState.presetIdx === idx
                  ? 'bg-charcoal text-white border-charcoal'
                  : 'bg-oat-50 text-warmgray border-oat-300'
              "
            >
              样本 {{ idx + 1 }}
            </button>
            <button
              @click="triggerInspUpload"
              class="px-2.5 py-1 rounded-lg text-[11.5px] font-medium border border-terracotta/50 bg-terracotta-light/60 text-terracotta-dark hover:bg-terracotta-light transition"
            >
              上传街拍
            </button>
            <input ref="inspFileInputRef" type="file" accept="image/*" class="hidden" @change="handleInspFileUpload" />
          </div>
        </div>

        <div class="relative rounded-2xl overflow-hidden border border-oat-300 aspect-[3/4] flatlay-canvas p-5 flex flex-col justify-between">
          <div class="flex items-center justify-between z-10">
            <span class="px-2.5 py-1 rounded-full bg-white/90 backdrop-blur-sm border border-oat-300 text-[11px] font-medium text-charcoal">
              {{ inspUploadedPreview ? '自定义上传街拍' : currentPreset.sourceTag }}
            </span>
            <span class="font-mono text-[11px] text-warmgray bg-white/80 px-2 py-0.5 rounded-md">点击红点查看拆解</span>
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
              class="absolute z-20 -translate-x-1/2 -translate-y-1/2 group"
              :style="{ left: item.posX + '%', top: item.posY + '%' }"
            >
              <span class="hotspot-ring absolute inset-0 rounded-full bg-terracotta" />
              <span
                class="relative flex items-center justify-center w-6 h-6 rounded-full text-[10px] font-mono font-semibold border-2 border-white shadow-md transition"
                :class="inspirationState.activeHotspot === idx ? 'bg-charcoal text-white scale-110' : 'bg-terracotta text-white'"
              >
                {{ idx + 1 }}
              </span>
              <span
                class="absolute left-7 top-1/2 -translate-y-1/2 whitespace-nowrap px-2 py-0.5 rounded-md bg-charcoal/90 text-white text-[10.5px] shadow-md pointer-events-none"
              >
                {{ item.slotLabel }}
              </span>
            </button>
          </div>

          <div class="z-10 bg-white/90 backdrop-blur-sm rounded-xl p-3 border border-oat-300/80">
            <div class="font-serif font-semibold text-[14px] text-charcoal">
              {{ inspUploadedPreview ? inspirationState.overallStyle : currentPreset.title }}
            </div>
            <div class="text-[11.5px] text-warmgray mt-0.5">
              {{ inspUploadedPreview ? '多模态拆解 · 自动检索个人衣橱与平替' : currentPreset.subtitle }}
            </div>
          </div>

          <div v-if="inspirationState.isAnalyzing" class="absolute inset-0 z-30 bg-charcoal/20 backdrop-blur-[1px]">
            <div class="scan-beam w-full h-20 bg-gradient-to-b from-transparent via-terracotta/40 to-terracotta/80 border-b-2 border-terracotta" />
          </div>
        </div>
      </div>

      <!-- Right Breakdown & Matching Results -->
      <div class="lg:col-span-7 space-y-5">
        <div class="bg-white rounded-3xl border border-oat-300 shadow-card p-6">
          <h3 class="font-serif text-[17px] font-semibold text-charcoal mb-3">告诉 AI 你的拆解需求与预算</h3>
          <textarea
            v-model="inspirationState.query"
            rows="2"
            class="w-full px-4 py-3 rounded-2xl bg-oat-50 border border-oat-300 focus:border-charcoal focus:bg-white outline-none text-[13.5px] text-charcoal resize-none transition"
            placeholder="输入自然语言需求..."
          />
          <div class="mt-4 flex flex-wrap items-center justify-between gap-4">
            <div class="flex items-center gap-3">
              <label class="text-[12.5px] font-medium text-warmgray">单品预算上限</label>
              <div class="relative flex items-center">
                <span class="absolute left-3 font-mono text-[13px] text-warmgray">¥</span>
                <input
                  v-model.number="inspirationState.budget"
                  type="number"
                  min="50"
                  max="2000"
                  step="10"
                  class="w-28 pl-7 pr-3 py-1.5 rounded-xl bg-oat-50 border border-oat-300 font-mono text-[13.5px] font-semibold text-charcoal outline-none focus:border-charcoal"
                />
              </div>
            </div>
            <button
              @click="runInspirationAnalysis"
              :disabled="inspirationState.isAnalyzing"
              class="px-5 py-2.5 rounded-full bg-charcoal hover:bg-charcoal-hover text-white text-[13.5px] font-medium shadow-sm transition flex items-center gap-2"
            >
              <AppIcon name="sparkles" :size="15" />
              {{ inspirationState.isAnalyzing ? '正在拆解并检索衣橱...' : '拆解灵感并检索' }}
            </button>
          </div>
        </div>

        <div class="bg-white rounded-3xl border border-oat-300 shadow-editorial p-6">
          <div class="mb-4">
            <h3 class="font-serif text-[17px] font-semibold text-charcoal">多模态拆解与衣橱比对结果</h3>
            <p class="text-[12px] text-warmgray mt-0.5">
              {{ inspirationState.notice || `已识别 ${inspirationState.items.length} 件单品 · 仅检索 @${authStore.user?.username} 的个人衣橱` }}
            </p>
          </div>

          <div class="space-y-3.5">
            <div
              v-for="(item, idx) in inspirationState.items"
              :key="item.name + idx"
              @click="inspirationState.activeHotspot = idx"
              class="p-4 rounded-2xl border transition cursor-pointer"
              :class="
                inspirationState.activeHotspot === idx
                  ? 'bg-oat-50 border-terracotta/70 shadow-sm'
                  : 'bg-white border-oat-200 hover:border-oat-400'
              "
            >
              <div class="flex items-center gap-3">
                <div class="w-12 h-12 rounded-xl bg-oat-100 border border-oat-200 p-1.5 shrink-0">
                  <GarmentSvg :type="item.svgType" :color="item.colorHex" class="w-full h-full" />
                </div>
                <div>
                  <div class="flex items-center gap-2 flex-wrap">
                    <span class="font-mono text-[11px] text-warmgray">0{{ idx + 1 }} {{ item.slotLabel }}</span>
                    <h4 class="font-medium text-[14px] text-charcoal">{{ item.name }}</h4>
                  </div>
                  <div v-if="item.matchedWardrobe" class="mt-1 flex items-center gap-2 text-[12px] flex-wrap">
                    <span
                      class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-emerald-50 text-emerald-700 border border-emerald-200 font-medium text-[11px]"
                    >
                      <AppIcon name="check" :size="11" />
                      衣橱已有 · {{ item.similarity }}% 相似
                    </span>
                    <span class="text-warmgray">
                      可直接复用「<strong class="text-charcoal font-medium">{{ item.matchedWardrobe.name }}</strong>」
                    </span>
                  </div>
                  <div v-else class="mt-1 flex items-center gap-2 text-[12px]">
                    <span class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-terracotta-light text-terracotta-dark font-medium text-[11px]">
                      衣橱缺失 · 建议补全
                    </span>
                    <span class="text-warmgray">为您匹配到预算内相似平替</span>
                  </div>
                </div>
              </div>

              <div v-if="!item.matchedWardrobe" class="mt-3 pt-3 border-t border-oat-200">
                <div v-if="item.products?.length" class="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
                  <div
                    v-for="prod in item.products"
                    :key="prod.id"
                    class="p-2.5 rounded-xl bg-white border border-oat-300/80 flex items-center gap-3 hover:border-terracotta transition"
                  >
                    <div class="w-11 h-11 rounded-lg bg-oat-100 p-1 shrink-0">
                      <GarmentVisual :image-url="prod.imageUrl" :svg-type="prod.svgType" :color-hex="prod.colorHex" :alt="prod.title" />
                    </div>
                    <div class="min-w-0 flex-1">
                      <div class="flex items-center gap-1.5">
                        <span class="text-[10px] px-1.5 py-0.5 rounded font-medium shrink-0" :class="platformBadgeClass(prod.platform)">
                          {{ prod.platformLabel }}
                        </span>
                        <span class="text-[12px] font-medium text-charcoal truncate">{{ prod.title }}</span>
                      </div>
                      <div class="flex items-center justify-between mt-1">
                        <span class="font-mono font-semibold text-[13px] text-terracotta">¥{{ prod.price }}</span>
                        <button
                          @click.stop="wardrobeStore.activeProductModal = prod"
                          class="text-[11px] font-medium text-charcoal hover:text-terracotta underline"
                        >
                          去看看 ↗
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
                <div v-else class="text-[12px] text-warmgray py-1">
                  当前预算 ¥{{ inspirationState.budget }} 以内暂无完全匹配商品。
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
