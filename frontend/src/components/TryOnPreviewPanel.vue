<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useLookbookStore } from '@/stores/lookbook';
import { useToastStore } from '@/stores/toast';
import type { OutfitPlanVo, TryOnJob, VisualPieceFeature } from '@/types';
import AppIcon from './AppIcon.vue';
import GarmentVisual from './GarmentVisual.vue';

const props = defineProps<{
  plan: OutfitPlanVo;
}>();

const router = useRouter();
const wardrobeStore = useWardrobeStore();
const lookbookStore = useLookbookStore();
const toast = useToastStore();

const stylePreset = ref<'studio_white' | 'street_shanghai' | 'window_light' | 'magazine_editorial'>('studio_white');
const generationMode = ref<'auto' | 'hd' | 'chat_stream'>('auto');
const customPrompt = ref('');
const useReferencePhoto = ref(true);
const submitting = ref(false);
const uploadingRef = ref(false);
const applyingCoverId = ref<number | null>(null);
const deletingJobId = ref<number | null>(null);
const activePieceSlotKey = ref<string | null>(null);
const showPrompt = ref(false);
const refInput = ref<HTMLInputElement | null>(null);
const nowTick = ref(Date.now());

let ticker: number | null = null;

const STYLE_PRESETS = [
  { id: 'studio_white', label: '极简纯白棚拍' },
  { id: 'street_shanghai', label: '安福路自然街拍' },
  { id: 'window_light', label: '画廊柔光大片' },
  { id: 'magazine_editorial', label: '高奢杂志 Lookbook' },
] as const;

const GENERATION_MODES = [
  { id: 'auto', label: '极速 1:1（省流防超时）' },
  { id: 'hd', label: '高清 2:3 杂志' },
  { id: 'chat_stream', label: '流式对话出图（SSE）' },
] as const;

const activeJobForPlan = computed<TryOnJob | null>(() => {
  return (
    lookbookStore.activeTryOnJobs.find((j) => j.planId === props.plan.id) ||
    lookbookStore.activeTryOnJobs[0] ||
    null
  );
});

const isGenerating = computed(() => submitting.value || !!activeJobForPlan.value);

const elapsedSeconds = computed(() => {
  const job = activeJobForPlan.value;
  if (!job?.createdAt) return 0;
  const started = new Date(job.createdAt).getTime();
  if (Number.isNaN(started)) return 0;
  return Math.max(1, Math.floor((nowTick.value - started) / 1000));
});

const selectedJob = computed<TryOnJob | null>(() => {
  if (lookbookStore.selectedTryOnJobId) {
    const found = lookbookStore.tryOnJobs.find((j) => j.id === lookbookStore.selectedTryOnJobId);
    if (found) return found;
  }
  // Prefer the latest succeeded job for the current plan
  const planJob = lookbookStore.tryOnJobs.find(
    (j) => j.planId === props.plan.id && j.status === 'SUCCEEDED' && !!j.previewImageUrl
  );
  if (planJob) return planJob;
  return lookbookStore.tryOnJobs.find((j) => j.status === 'SUCCEEDED' && !!j.previewImageUrl) || null;
});

const activePreviewUrl = computed(() => {
  if (selectedJob.value?.previewImageUrl) return selectedJob.value.previewImageUrl;
  return props.plan.tryOnImageUrl || '';
});

const modelRefUrl = computed(() => wardrobeStore.aiConfig.modelReferenceUrl || '');
const hasImageApiReady = computed(() => wardrobeStore.aiConfig.hasApiKey && wardrobeStore.aiConfig.enabled);

const displayPieces = computed<VisualPieceFeature[]>(() => {
  const reportPieces = selectedJob.value?.visualReport?.pieces;
  if (reportPieces && reportPieces.length > 0 && selectedJob.value?.planId === props.plan.id) {
    return reportPieces;
  }
  return (props.plan.slots || []).map((slot) => {
    const color = slot.item.color || '';
    const material = slot.item.material || '';
    const tags = (slot.item.styleTags || []).slice(0, 2).join(' · ');
    const parts = [color ? `${color}色调` : '', material ? `${material}质感` : '', tags].filter(Boolean);
    return {
      slotKey: slot.slotKey,
      slotLabel: slot.slotLabel,
      itemId: slot.item.id,
      name: slot.item.name,
      imageUrl: slot.item.processedImageUrl,
      colorHex: slot.item.colorHex,
      svgType: slot.item.svgType,
      zhSummary: parts.length ? parts.join(' · ') : slot.item.name,
      enPrompt: '',
      visualGrounded: false,
    };
  });
});

const activePiece = computed(
  () => displayPieces.value.find((p) => p.slotKey === activePieceSlotKey.value) || null
);

const bodyZonePosition = computed(() => {
  switch (activePieceSlotKey.value) {
    case 'outerwear':
    case 'top':
      return { top: '28%', left: '50%', label: '上身区域' };
    case 'bottom':
      return { top: '60%', left: '50%', label: '下装区域' };
    case 'shoes':
      return { top: '87%', left: '50%', label: '足部鞋履' };
    case 'accessory':
      return { top: '46%', left: '68%', label: '包配细节' };
    default:
      return null;
  }
});

watch(
  () => props.plan.id,
  () => {
    showPrompt.value = false;
    activePieceSlotKey.value = null;
  }
);

onMounted(() => {
  void lookbookStore.refreshTryOnJobs();
  ticker = window.setInterval(() => {
    nowTick.value = Date.now();
  }, 1000);
});

onUnmounted(() => {
  if (ticker !== null) {
    window.clearInterval(ticker);
    ticker = null;
  }
});

async function handleUploadRef(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  uploadingRef.value = true;
  try {
    await wardrobeStore.uploadModelReference(file);
    useReferencePhoto.value = true;
    toast.show('本人 / 模特参考图已上传，生成试穿图时将自动携带该参考形象');
  } catch (err: any) {
    toast.show(err.message || '参考图上传失败');
  } finally {
    uploadingRef.value = false;
    input.value = '';
  }
}

async function handleGenerate() {
  if (isGenerating.value) return;
  submitting.value = true;
  try {
    const job = await lookbookStore.submitTryOnJob({
      stylePreset: stylePreset.value,
      customPrompt: customPrompt.value.trim() || undefined,
      useReferencePhoto: useReferencePhoto.value && !!modelRefUrl.value,
      generationMode: generationMode.value,
    });
    if (job) {
      toast.show('生图任务已提交后台队列，刷新或切换页面都会自动保留进度与成图');
    }
  } catch (e: any) {
    toast.show(e.message || '提交生图任务失败');
  } finally {
    submitting.value = false;
  }
}

function selectHistoryJob(job: TryOnJob) {
  lookbookStore.selectedTryOnJobId = job.id;
  showPrompt.value = false;
  activePieceSlotKey.value = null;
}

function togglePieceHighlight(slotKey: string) {
  activePieceSlotKey.value = activePieceSlotKey.value === slotKey ? null : slotKey;
}

async function handleApplyAsCover(job: TryOnJob) {
  if (!job.previewImageUrl || applyingCoverId.value !== null) return;
  applyingCoverId.value = job.id;
  try {
    await lookbookStore.applyTryOnJobAsCover(job.id);
    lookbookStore.selectedTryOnJobId = job.id;
    toast.show('已将该生成图设为当前方案封面');
  } catch (e: any) {
    toast.show(e.message || '设置封面失败');
  } finally {
    applyingCoverId.value = null;
  }
}

async function handleDeleteJob(job: TryOnJob) {
  if (deletingJobId.value !== null) return;
  deletingJobId.value = job.id;
  try {
    await lookbookStore.deleteTryOnJob(job.id);
    toast.show('已删除该条生成记录');
  } catch (e: any) {
    toast.show(e.message || '删除记录失败');
  } finally {
    deletingJobId.value = null;
  }
}

function formatShortTime(ts?: string) {
  if (!ts) return '';
  const d = new Date(ts);
  if (Number.isNaN(d.getTime())) return '';
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${hh}:${mm}`;
}

function downloadPreview() {
  if (!activePreviewUrl.value) return;
  const a = document.createElement('a');
  a.href = activePreviewUrl.value;
  a.target = '_blank';
  a.download = `alta-tryon-${props.plan.id}.png`;
  a.click();
}
</script>

<template>
  <div class="space-y-4">
    <!-- Top Controls: Style Preset + Model Reference Strip -->
    <div class="rounded-2xl bg-white border border-black/[0.08] p-4 space-y-3.5">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <div class="flex items-center gap-2 flex-wrap">
          <span class="text-[13px] font-semibold text-black">杂志上身预览工坊</span>
          <span
            class="px-2 py-0.5 rounded-full text-[10.5px] font-mono border"
            :class="
              hasImageApiReady
                ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                : 'bg-[#F5F5F5] text-[#666666] border-black/10'
            "
          >
            {{
              hasImageApiReady
                ? `中转站已激活 · ${wardrobeStore.aiConfig.imageModel || 'gpt-image-1'}`
                : '演示降级模式（未配置生图 Key）'
            }}
          </span>
          <span class="px-2 py-0.5 rounded-full bg-[#F5F5F5] text-[#666666] text-[10.5px]">
            异步持久化 · 刷新不丢图
          </span>
        </div>
        <button
          type="button"
          @click="router.push({ path: '/profile', query: { tab: 'settings', panel: 'ai' } })"
          class="text-[11.5px] text-[#666666] hover:text-black underline"
        >
          配置中转站 / 生图模型 →
        </button>
      </div>

      <!-- Editorial Atmosphere Presets & Speed Mode -->
      <div class="flex flex-wrap items-center justify-between gap-2">
        <div class="flex flex-wrap gap-1.5">
          <button
            v-for="p in STYLE_PRESETS"
            :key="p.id"
            type="button"
            @click="stylePreset = p.id"
            class="px-3 py-1 rounded-full text-[12px] font-medium transition"
            :class="stylePreset === p.id ? 'bg-black text-white' : 'bg-[#F5F5F5] text-[#333333] hover:bg-[#EBEBEB]'"
          >
            {{ p.label }}
          </button>
        </div>

        <div class="inline-flex items-center rounded-full bg-[#F2F2F2] p-0.5 border border-black/[0.06]">
          <button
            v-for="m in GENERATION_MODES"
            :key="m.id"
            type="button"
            @click="generationMode = m.id"
            class="px-2.5 py-1 rounded-full text-[11px] font-medium transition"
            :class="generationMode === m.id ? 'bg-white text-black shadow-xs' : 'text-[#666666] hover:text-black'"
          >
            {{ m.label }}
          </button>
        </div>
      </div>

      <!-- User Model Reference Photo Card -->
      <div class="flex flex-wrap items-center justify-between gap-3 p-3 rounded-2xl bg-[#FAFAFA] border border-black/[0.06]">
        <div class="flex items-center gap-3 min-w-0">
          <div class="w-12 h-12 rounded-xl bg-white border border-black/10 overflow-hidden flex items-center justify-center shrink-0">
            <img v-if="modelRefUrl" :src="modelRefUrl" alt="参考图" class="w-full h-full object-cover" />
            <AppIcon v-else name="user" :size="18" class="text-[#999999]" />
          </div>
          <div class="min-w-0">
            <div class="text-[12.5px] font-medium text-black">
              {{ modelRefUrl ? '已绑定本人 / 模特参考照' : '上传本人或模特全身参考照（可选）' }}
            </div>
            <div class="text-[11px] text-[#666666] truncate">
              {{
                modelRefUrl
                  ? '生图时将携带该人物参考照与本套全部单品原图，并先由 VLM 提取领型/版型特征'
                  : '未上传时将使用默认东方时尚模特，同时携带本套全部单品特征参与渲染'
              }}
            </div>
          </div>
        </div>

        <div class="flex items-center gap-2 shrink-0">
          <label v-if="modelRefUrl" class="inline-flex items-center gap-1.5 text-[11.5px] text-[#333333] cursor-pointer">
            <input v-model="useReferencePhoto" type="checkbox" class="accent-black rounded" />
            启用参考图
          </label>
          <input ref="refInput" type="file" accept="image/jpeg,image/png,image/webp" class="hidden" @change="handleUploadRef" />
          <button
            type="button"
            @click="refInput?.click()"
            :disabled="uploadingRef"
            class="px-3 py-1.5 rounded-full bg-white hover:bg-black hover:text-white border border-black/15 text-[11.5px] font-medium text-black transition"
          >
            {{ uploadingRef ? '上传中...' : modelRefUrl ? '更换参考照' : '上传参考照' }}
          </button>
        </div>
      </div>

      <!-- Optional Custom Direction + Generate CTA -->
      <div class="flex flex-col sm:flex-row gap-2">
        <input
          v-model="customPrompt"
          @keyup.enter="handleGenerate"
          :disabled="isGenerating"
          type="text"
          placeholder="可选补充氛围要求，如：自然步行抓拍 / 低饱和胶片感 / 手持咖啡..."
          class="flex-1 px-4 py-2 rounded-full bg-[#F5F5F5] focus:bg-white border border-transparent focus:border-black outline-none text-[12.5px] transition disabled:opacity-60"
        />
        <button
          type="button"
          @click="handleGenerate"
          :disabled="isGenerating"
          class="px-5 py-2 rounded-full bg-black hover:bg-[#262626] text-white text-[13px] font-medium transition flex items-center justify-center gap-1.5 shrink-0 disabled:opacity-60 disabled:cursor-not-allowed"
        >
          <AppIcon v-if="isGenerating" name="loader" :size="15" class="animate-spin" />
          <AppIcon v-else name="sparkles" :size="15" />
          {{
            isGenerating
              ? `后台渲染中 (${elapsedSeconds}s)...`
              : activePreviewUrl
                ? '再生成一张上身图'
                : '一键生成 AI 上身预览图'
          }}
        </button>
      </div>
    </div>

    <!-- Main Editorial Preview Stage -->
    <div class="relative min-h-[420px] rounded-3xl bg-white border border-black/[0.08] overflow-hidden flex flex-col items-center justify-center p-5">
      <!-- Persistent Async Generating Overlay (Survives Page Refresh!) -->
      <div
        v-if="isGenerating"
        class="absolute inset-0 z-20 bg-white/88 backdrop-blur-sm flex flex-col items-center justify-center gap-3 p-6 text-center"
      >
        <div class="w-40 h-56 rounded-2xl bg-[#F5F5F5] border border-black/10 relative overflow-hidden">
          <div class="scan-beam w-full h-16 bg-gradient-to-b from-transparent via-black/15 to-black/35 border-b-2 border-black" />
        </div>
        <div class="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-black text-white text-[11.5px] font-mono">
          <span class="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse" />
          <span>JOB #{{ activeJobForPlan?.id || '...' }} · 已耗时 {{ elapsedSeconds }}s</span>
        </div>
        <div class="text-[14px] font-semibold text-black">
          {{
            elapsedSeconds <= 6
              ? `正在通过 VLM 解析本套 ${plan.slots.length} 件单品的领型、面料与版型特征…`
              : '正在融合视觉特征与单品参考图，合成杂志级全身试穿大片…'
          }}
        </div>
        <p class="text-[12px] text-[#666666] max-w-sm leading-relaxed">
          任务已持久化到数据库，<strong>现在刷新页面或去逛衣橱/灵感广场都不会中断</strong>，完成后将自动落盘存档。
        </p>
      </div>

      <!-- Result Showcase -->
      <template v-if="activePreviewUrl">
        <div class="relative w-full flex flex-col items-center">
          <div class="relative max-w-[360px] w-full aspect-[3/4] rounded-2xl bg-[#FAFAFA] overflow-hidden shadow-editorial">
            <img :src="activePreviewUrl" :alt="plan.themeTitle" class="w-full h-full object-cover object-top" />

            <!-- Body-Zone Focus Ring & Callout when user clicks a piece below -->
            <div
              v-if="bodyZonePosition && activePiece"
              class="absolute -translate-x-1/2 -translate-y-1/2 z-10 pointer-events-none flex flex-col items-center gap-1 transition-all duration-300"
              :style="{ top: bodyZonePosition.top, left: bodyZonePosition.left }"
            >
              <span class="w-8 h-8 rounded-full border-2 border-white bg-black/25 ring-2 ring-black/60 animate-pulse" />
              <span class="px-2.5 py-0.5 rounded-full bg-black/85 text-white text-[10.5px] font-medium whitespace-nowrap backdrop-blur-sm shadow-sm">
                {{ activePiece.slotLabel }} · {{ activePiece.name }}
              </span>
            </div>

            <div class="absolute top-3 right-3 flex items-center gap-1.5">
              <button
                v-if="selectedJob && selectedJob.previewImageUrl !== plan.tryOnImageUrl"
                type="button"
                @click="handleApplyAsCover(selectedJob)"
                :disabled="applyingCoverId === selectedJob.id"
                class="inline-flex items-center gap-1 px-2.5 h-8 rounded-full bg-black/85 hover:bg-black text-white text-[11px] font-medium backdrop-blur-md transition disabled:opacity-60"
              >
                <AppIcon v-if="applyingCoverId === selectedJob.id" name="loader" :size="12" class="animate-spin" />
                {{ applyingCoverId === selectedJob.id ? '保存中...' : '设为本套封面' }}
              </button>
              <button
                type="button"
                @click="downloadPreview"
                title="下载高清预览图"
                class="w-8 h-8 rounded-full bg-white/90 hover:bg-black hover:text-white text-black flex items-center justify-center shadow-sm transition"
              >
                <AppIcon name="download" :size="14" />
              </button>
            </div>
          </div>

          <!-- Interactive Piece Comparison Strip ("本套单品视觉对照") -->
          <div v-if="displayPieces.length > 0" class="mt-4 w-full max-w-lg space-y-2">
            <div class="flex items-center justify-between text-[11.5px]">
              <span class="font-medium text-black">本套单品视觉对照</span>
              <span class="text-[#777777]">点击单品卡片可定位模特穿着区域与细节解析</span>
            </div>
            <div class="grid grid-cols-3 sm:grid-cols-5 gap-2">
              <button
                v-for="piece in displayPieces"
                :key="piece.slotKey + '-' + (piece.itemId || piece.name)"
                type="button"
                @click="togglePieceHighlight(piece.slotKey)"
                class="rounded-xl p-1.5 border text-left transition flex flex-col items-center focus:outline-none"
                :class="
                  activePieceSlotKey === piece.slotKey
                    ? 'border-black bg-[#F2F2F2] ring-1 ring-black'
                    : 'border-black/[0.08] bg-[#FAFAFA] hover:border-black/30'
                "
              >
                <div class="w-full aspect-square rounded-lg bg-white p-1 flex items-center justify-center overflow-hidden">
                  <GarmentVisual
                    :image-url="piece.imageUrl"
                    :svg-type="piece.svgType"
                    :color-hex="piece.colorHex"
                    :alt="piece.name"
                  />
                </div>
                <span class="mt-1 text-[10px] font-medium text-black truncate w-full text-center">{{ piece.slotLabel }}</span>
              </button>
            </div>
          </div>

          <!-- Plan D: Structured Chinese AI Visual Feature Analysis Report -->
          <div
            v-if="selectedJob"
            class="mt-3.5 w-full max-w-lg rounded-2xl bg-[#FAFAFA] border border-black/[0.08] p-3.5 space-y-2.5 text-left"
          >
            <div class="flex flex-wrap items-center justify-between gap-2">
              <div class="flex items-center gap-1.5">
                <AppIcon name="sparkles" :size="13" class="text-black" />
                <span class="text-[12.5px] font-semibold text-black">AI 视觉特征解析报告</span>
              </div>
              <div class="flex flex-wrap items-center gap-1.5">
                <span
                  class="px-2 py-0.5 rounded-full text-[10px] font-medium border"
                  :class="
                    selectedJob.visualReport?.usedRealVlm
                      ? 'bg-black text-white border-black'
                      : 'bg-white text-[#555555] border-black/10'
                  "
                >
                  {{
                    selectedJob.visualReport?.usedRealVlm
                      ? `VLM 看图反推 · 已解析 ${selectedJob.visualReport.referencedGarmentCount} 件`
                      : '衣橱属性解析'
                  }}
                </span>
                <span
                  class="px-2 py-0.5 rounded-full text-[10px] font-medium bg-white border border-black/10 text-[#444444]"
                >
                  {{ selectedJob.visualReport?.usedGarmentImagesInGen ? '原图垫图融合' : '视觉描述引导' }}
                </span>
              </div>
            </div>

            <div
              v-if="selectedJob.visualReport"
              class="flex flex-wrap items-center gap-1.5 text-[11px] text-[#555555]"
            >
              <span class="px-2 py-0.5 rounded-full bg-white border border-black/[0.06]">
                {{ selectedJob.visualReport.bodyProfile }}
              </span>
              <span class="px-2 py-0.5 rounded-full bg-white border border-black/[0.06]">
                {{ selectedJob.visualReport.sceneLabel }}
              </span>
              <span class="text-[#333333] leading-relaxed">
                {{ selectedJob.visualReport.overallPalette }}
              </span>
            </div>

            <div v-if="displayPieces.length > 0" class="space-y-1.5 pt-1 border-t border-black/[0.06]">
              <div
                v-for="piece in displayPieces"
                :key="'rpt-' + piece.slotKey"
                @click="togglePieceHighlight(piece.slotKey)"
                class="flex items-start gap-2 px-2.5 py-1.5 rounded-xl cursor-pointer transition"
                :class="
                  activePieceSlotKey === piece.slotKey
                    ? 'bg-white border border-black/25 shadow-2xs'
                    : 'hover:bg-white/80'
                "
              >
                <span class="shrink-0 mt-0.5 px-1.5 py-0.5 rounded-md bg-black text-white text-[10px] font-medium">
                  {{ piece.slotLabel }}
                </span>
                <div class="min-w-0 text-[11.5px] leading-relaxed">
                  <span class="font-medium text-black">{{ piece.name }}</span>
                  <span class="text-[#666666]"> — {{ piece.zhSummary }}</span>
                </div>
              </div>
            </div>

            <div
              v-if="selectedJob.notice || selectedJob.promptUsed"
              class="pt-2 border-t border-black/[0.06] flex flex-wrap items-center justify-between gap-2 text-[11px] text-[#666666]"
            >
              <span class="flex-1 min-w-[200px]">{{ selectedJob.notice }}</span>
              <button
                v-if="selectedJob.promptUsed"
                type="button"
                @click="showPrompt = !showPrompt"
                class="text-black font-medium shrink-0 underline underline-offset-2"
              >
                {{ showPrompt ? '收起底层 Prompt' : '查看底层英文 Prompt' }}
              </button>
            </div>
          </div>

          <div
            v-if="showPrompt && selectedJob?.promptUsed"
            class="mt-2 w-full max-w-lg p-3 rounded-2xl bg-[#F5F5F5] font-mono text-[11px] text-[#333333] leading-relaxed break-words"
          >
            {{ selectedJob.promptUsed }}
          </div>
        </div>
      </template>

      <!-- Empty State Before First Generation -->
      <div v-else class="py-10 text-center space-y-3 max-w-md">
        <div class="w-14 h-14 mx-auto rounded-full bg-[#F5F5F5] flex items-center justify-center text-black">
          <AppIcon name="image" :size="24" />
        </div>
        <h4 class="text-[16px] font-semibold text-black">把这套搭配穿在模特或你自己身上看看</h4>
        <p class="text-[12.5px] text-[#666666] leading-relaxed">
          点击上方「一键生成 AI 上身预览图」：后台异步队列将自动持久保存每张成图与任务状态，无需手动点击保存，刷新页面也不会丢失。
        </p>
      </div>
    </div>

    <!-- Auto-Saved Generation History Filmstrip (Midjourney / 即梦 / Alta style) -->
    <div
      v-if="lookbookStore.tryOnJobs.length > 0"
      class="rounded-2xl bg-white border border-black/[0.08] p-4 space-y-3"
    >
      <div class="flex items-center justify-between gap-2">
        <div class="flex items-center gap-2">
          <span class="text-[12.5px] font-semibold text-black">近期生成记录（自动存档）</span>
          <span class="font-mono text-[11px] text-[#666666]">{{ lookbookStore.tryOnJobs.length }} 条</span>
        </div>
        <span class="text-[11px] text-[#666666]">未点收藏也会保留，点击缩略图随时对比</span>
      </div>

      <div class="flex gap-2.5 overflow-x-auto pb-1">
        <div
          v-for="job in lookbookStore.tryOnJobs"
          :key="job.id"
          @click="selectHistoryJob(job)"
          class="group relative w-[92px] shrink-0 rounded-xl overflow-hidden border cursor-pointer transition"
          :class="
            selectedJob?.id === job.id
              ? 'border-black ring-1 ring-black'
              : 'border-black/[0.08] hover:border-black/40'
          "
        >
          <div class="aspect-[3/4] bg-[#F5F5F5] relative flex items-center justify-center">
            <img
              v-if="job.previewImageUrl"
              :src="job.previewImageUrl"
              :alt="job.planThemeTitle || '试穿记录'"
              class="w-full h-full object-cover object-top"
            />
            <div
              v-else-if="job.status === 'QUEUED' || job.status === 'PROCESSING'"
              class="flex flex-col items-center gap-1.5 px-2 text-center"
            >
              <span class="w-4 h-4 rounded-full border-2 border-black border-t-transparent animate-spin" />
              <span class="text-[10px] font-medium text-black">生成中</span>
            </div>
            <div v-else class="px-2 text-center text-[10px] text-[#666666]">
              生成失败
            </div>

            <span
              class="absolute top-1.5 left-1.5 px-1.5 py-0.5 rounded-full text-[9px] font-mono"
              :class="
                job.status === 'PROCESSING' || job.status === 'QUEUED'
                  ? 'bg-black text-white'
                  : job.usedRealImageModel
                    ? 'bg-black/80 text-white'
                    : 'bg-white/90 text-black'
              "
            >
              {{
                job.status === 'PROCESSING' || job.status === 'QUEUED'
                  ? '进行中'
                  : job.status === 'FAILED'
                    ? '失败'
                    : job.usedRealImageModel
                      ? 'AI'
                      : '样片'
              }}
            </span>

            <button
              v-if="job.status !== 'PROCESSING' && job.status !== 'QUEUED'"
              type="button"
              @click.stop="handleDeleteJob(job)"
              :disabled="deletingJobId === job.id"
              title="删除该条记录"
              class="absolute top-1.5 right-1.5 w-5 h-5 rounded-full bg-white/90 hover:bg-black hover:text-white text-[#666666] opacity-0 group-hover:opacity-100 flex items-center justify-center transition disabled:opacity-60"
            >
              <AppIcon v-if="deletingJobId === job.id" name="loader" :size="10" class="animate-spin" />
              <AppIcon v-else name="x" :size="11" />
            </button>
          </div>
          <div class="px-1.5 py-1 bg-white">
            <div class="text-[10px] text-black truncate">
              {{ job.planThemeTitle || `方案 #${job.planId}` }}
            </div>
            <div class="text-[9.5px] font-mono text-[#666666] flex items-center justify-between">
              <span>#{{ job.id }}</span>
              <span>{{ formatShortTime(job.createdAt) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
