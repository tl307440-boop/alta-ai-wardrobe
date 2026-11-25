<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useLookbookStore } from '@/stores/lookbook';
import { useToastStore } from '@/stores/toast';
import {
  QUICK_SCENARIOS,
  REFINE_CHIPS,
  WEATHER_PRESETS,
  categoryLabel,
  platformBadgeClass,
} from '@/utils/constants';
import type { CollageNode } from '@/types';
import AppIcon from '@/components/AppIcon.vue';
import GarmentVisual from '@/components/GarmentVisual.vue';
import FlatLayCollageCanvas from '@/components/FlatLayCollageCanvas.vue';
import TryOnPreviewPanel from '@/components/TryOnPreviewPanel.vue';

const CANVAS_MODE_KEY = 'alta_lookbook_canvas_mode';

const route = useRoute();
const router = useRouter();
const wardrobeStore = useWardrobeStore();
const lookbookStore = useLookbookStore();
const toast = useToastStore();

function resolveInitialMode(): 'grid' | 'collage' | 'tryon' {
  const qMode = String(route.query.mode || '');
  if (qMode === 'grid' || qMode === 'collage' || qMode === 'tryon') return qMode;
  try {
    const saved = localStorage.getItem(CANVAS_MODE_KEY);
    if (saved === 'grid' || saved === 'collage' || saved === 'tryon') return saved;
  } catch {
    // Ignore storage error
  }
  return 'grid';
}

const refineInput = ref('');
const canvasMode = ref<'grid' | 'collage' | 'tryon'>(resolveInitialMode());
const savingLayout = ref(false);

function syncLookbookUrl(mode: 'grid' | 'collage' | 'tryon', planId?: number | null) {
  try {
    localStorage.setItem(CANVAS_MODE_KEY, mode);
  } catch {
    // Ignore storage error
  }
  const nextQuery: Record<string, string> = { mode };
  if (planId) {
    nextQuery.planId = String(planId);
  }
  if (route.query.mode !== nextQuery.mode || route.query.planId !== nextQuery.planId) {
    router.replace({ path: '/lookbook', query: nextQuery });
  }
}

watch(canvasMode, (mode) => {
  syncLookbookUrl(mode, lookbookStore.currentPlan?.id);
});

watch(
  () => lookbookStore.currentPlan?.id,
  (id) => {
    if (id) {
      syncLookbookUrl(canvasMode.value, id);
    }
  }
);

watch(
  () => route.query.mode,
  (qMode) => {
    if (qMode === 'grid' || qMode === 'collage' || qMode === 'tryon') {
      canvasMode.value = qMode;
    }
  }
);

onMounted(async () => {
  const qPlanId = Number(route.query.planId);
  if (qPlanId > 0 && lookbookStore.currentPlan?.id !== qPlanId) {
    await lookbookStore.restorePlanById(qPlanId);
  }
  await lookbookStore.refreshTryOnJobs();
  if (lookbookStore.activeTryOnJobs.length > 0 && !route.query.mode) {
    canvasMode.value = 'tryon';
  }
  syncLookbookUrl(canvasMode.value, lookbookStore.currentPlan?.id);
});

const anchorItem = computed(
  () => wardrobeStore.items.find((i) => i.id === lookbookStore.anchorItemId) || wardrobeStore.items[0] || null
);

const ownedSlots = computed(() => (lookbookStore.currentPlan?.slots || []).filter((s) => !s.missing));
const missingSlot = computed(() => (lookbookStore.currentPlan?.slots || []).find((s) => s.missing) || null);

const filteredProducts = computed(() => {
  const cap = Number(lookbookStore.budget) || 300;
  const planProds = lookbookStore.currentPlan?.recommendedProducts || [];
  const inBudget = planProds.filter((p) => Number(p.price) <= cap);
  if (inBudget.length > 0) return inBudget;
  return wardrobeStore.products.filter((p) => Number(p.price) <= cap).slice(0, 3);
});

async function setTriggerMode(mode: 'weather' | 'nl' | 'anchor') {
  lookbookStore.triggerMode = mode;
  if (mode === 'weather') {
    await lookbookStore.generateRecommendation({ triggerType: 'daily_weather' });
  } else if (mode === 'anchor' && anchorItem.value) {
    lookbookStore.anchorItemId = anchorItem.value.id;
    await lookbookStore.generateRecommendation({ triggerType: 'anchor_item', anchorItemId: anchorItem.value.id });
  }
}

async function selectLiveWeather() {
  await wardrobeStore.useLiveWeather(true);
  lookbookStore.triggerMode = 'weather';
  await lookbookStore.generateRecommendation({ triggerType: 'daily_weather' });
  const w = wardrobeStore.activeWeather;
  toast.show(`已切换至「${w.city}」实时天气 (${w.condition} ${w.temp}°C)，搭配方案已重算`);
}

async function selectWeatherPreset(idx: number) {
  wardrobeStore.selectWeatherPreset(idx);
  lookbookStore.triggerMode = 'weather';
  await lookbookStore.generateRecommendation({ triggerType: 'daily_weather' });
  toast.show(`已切换至模拟场景：${WEATHER_PRESETS[idx].label} (${WEATHER_PRESETS[idx].temp}°C)，搭配方案已重算`);
}

async function generateNLLook() {
  lookbookStore.triggerMode = 'nl';
  await lookbookStore.generateRecommendation({
    triggerType: 'nl_query',
    userQuery: lookbookStore.nlQuery,
  });
  toast.show('已根据自然语言指令生成专属穿搭方案');
}

async function applyQuickScenario(sc: (typeof QUICK_SCENARIOS)[number]) {
  lookbookStore.nlQuery = sc.query;
  await generateNLLook();
}

async function changeAnchorById(id: number) {
  lookbookStore.anchorItemId = id;
  await lookbookStore.generateRecommendation({ triggerType: 'anchor_item', anchorItemId: id });
  const item = wardrobeStore.items.find((i) => i.id === id);
  if (item) toast.show(`已切换核心锚点单品为「${item.name}」`);
}

async function handleSwapSlot(slotKey: string, currentItemId: number) {
  try {
    await lookbookStore.swapSlot(slotKey, currentItemId > 0 ? [currentItemId] : []);
    toast.show('已完成单品局部替换');
  } catch (e: any) {
    toast.show(e.message || '暂无更多同品类单品可替换');
  }
}

async function handleSaveCollageLayout(nodes: CollageNode[]) {
  savingLayout.value = true;
  try {
    await lookbookStore.saveCollageLayout(nodes);
    toast.show('自由拼贴画板排版已保存，收藏后打开仍将还原当前布局');
  } catch (e: any) {
    toast.show(e.message || '保存排版失败');
  } finally {
    savingLayout.value = false;
  }
}

async function applyRefinement(text: string) {
  const cmd = (text || '').trim();
  if (!cmd) return;
  refineInput.value = '';
  if (cmd.includes('200') && cmd.includes('预算')) {
    lookbookStore.budget = 200;
  }
  await lookbookStore.generateRecommendation({
    refineInstruction: cmd,
    basePlanId: lookbookStore.currentPlan?.id,
  });
  toast.show(`微调完成：${cmd}`);
}

async function handleToggleFavorite() {
  const updated = await lookbookStore.toggleFavorite();
  if (updated?.favorite) {
    toast.show('已收藏该穿搭方案（已强化对应风格权重）', '去收藏馆', () => {
      router.push({ path: '/calendar', query: { tab: 'favorites' } });
    });
  } else {
    toast.show('已取消收藏');
  }
}

async function handleDislikePlan() {
  await lookbookStore.dislikePlan();
  toast.show('已记录避雷偏好（自动降低该组合风格权重）并为你换了一套');
}

async function handleToggleReviveIdle() {
  lookbookStore.reviveIdle = !lookbookStore.reviveIdle;
  await lookbookStore.generateRecommendation({
    reviveIdle: lookbookStore.reviveIdle,
  });
  toast.show(
    lookbookStore.reviveIdle
      ? '已开启「盘活闲置模式」：优先把压箱底单品搭出门'
      : '已关闭盘活闲置模式，恢复标准个性化综合打分'
  );
}

async function handleRecordToday() {
  await lookbookStore.recordToday();
  toast.show('已记录为今日穿搭，并同步更新单品穿着次数', '查看日历', () => {
    router.push('/calendar');
  });
}
</script>

<template>
  <section class="space-y-5">
    <!-- Top Trigger & Budget Control Bar -->
    <div class="bg-white rounded-3xl border border-oat-300 shadow-card p-5 space-y-4">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div class="inline-flex flex-wrap p-1 rounded-2xl bg-oat-100 border border-oat-300 gap-1">
          <button
            @click="setTriggerMode('weather')"
            class="px-4 py-1.5 rounded-xl text-[13px] font-medium transition flex items-center gap-1.5"
            :class="lookbookStore.triggerMode === 'weather' ? 'bg-charcoal text-white shadow-sm' : 'text-warmgray hover:text-charcoal'"
          >
            <AppIcon name="cloud-sun" :size="15" />
            今日天气驱动
          </button>
          <button
            @click="setTriggerMode('nl')"
            class="px-4 py-1.5 rounded-xl text-[13px] font-medium transition flex items-center gap-1.5"
            :class="lookbookStore.triggerMode === 'nl' ? 'bg-charcoal text-white shadow-sm' : 'text-warmgray hover:text-charcoal'"
          >
            <AppIcon name="message" :size="15" />
            自然语言场景定制
          </button>
          <button
            @click="setTriggerMode('anchor')"
            class="px-4 py-1.5 rounded-xl text-[13px] font-medium transition flex items-center gap-1.5"
            :class="lookbookStore.triggerMode === 'anchor' ? 'bg-charcoal text-white shadow-sm' : 'text-warmgray hover:text-charcoal'"
          >
            <AppIcon name="pin" :size="15" />
            单品锚点一衣多搭
          </button>
        </div>

        <div class="flex flex-wrap items-center gap-2.5 text-[12.5px]">
          <button
            type="button"
            @click="handleToggleReviveIdle"
            class="inline-flex items-center gap-1.5 px-3 py-1 rounded-full border text-[12px] font-medium transition"
            :class="
              lookbookStore.reviveIdle
                ? 'bg-black text-white border-black'
                : 'bg-white text-[#555555] border-oat-300 hover:border-black hover:text-black'
            "
          >
            <AppIcon name="sparkles" :size="13" />
            {{ lookbookStore.reviveIdle ? '盘活闲置中' : '盘活闲置' }}
          </button>

          <div class="flex items-center gap-2">
            <span class="text-warmgray">缺品导购预算 ≤</span>
            <div class="relative flex items-center">
              <span class="absolute left-2.5 font-mono text-warmgray text-[12px]">¥</span>
              <input
                v-model.number="lookbookStore.budget"
                type="number"
                min="100"
                max="1000"
                step="20"
                class="w-24 pl-6 pr-2 py-1 rounded-xl bg-oat-50 border border-oat-300 font-mono font-semibold text-charcoal outline-none focus:border-charcoal"
              />
            </div>
          </div>
        </div>
      </div>

      <!-- Mode 1: Weather -->
      <div v-if="lookbookStore.triggerMode === 'weather'" class="flex flex-wrap items-center gap-2.5 pt-2 border-t border-oat-200">
        <button
          @click="selectLiveWeather"
          class="px-3.5 py-1.5 rounded-full text-[12.5px] font-medium border transition flex items-center gap-1.5"
          :class="
            wardrobeStore.usingLiveWeather
              ? 'bg-emerald-50 border-emerald-400 text-emerald-800 shadow-sm'
              : 'bg-white border-oat-300 text-charcoal-soft hover:bg-oat-100'
          "
        >
          <span class="w-2 h-2 rounded-full bg-emerald-500" />
          {{ wardrobeStore.liveWeather.city }} 实时天气
          <span class="font-mono">{{ wardrobeStore.liveWeather.temp }}°C</span>
          <span v-if="wardrobeStore.liveWeather.humidity != null" class="text-[11px] opacity-80">
            · 体感 {{ wardrobeStore.liveWeather.apparentTemp ?? wardrobeStore.liveWeather.temp }}°C · 湿度 {{ wardrobeStore.liveWeather.humidity }}%
          </span>
        </button>

        <span class="text-[12px] text-warmgray ml-1">模拟温差演示：</span>
        <button
          v-for="(w, idx) in WEATHER_PRESETS"
          :key="w.id"
          @click="selectWeatherPreset(idx)"
          class="px-3.5 py-1.5 rounded-full text-[12.5px] font-medium border transition flex items-center gap-1.5"
          :class="
            !wardrobeStore.usingLiveWeather && wardrobeStore.weatherPresetIdx === idx
              ? 'bg-terracotta-light border-terracotta text-terracotta-dark'
              : 'bg-oat-50 border-oat-300 text-charcoal-soft hover:bg-oat-100'
          "
        >
          <AppIcon :name="w.icon" :size="14" />
          {{ w.label }} <span class="font-mono">{{ w.temp }}°C</span>
        </button>
      </div>

      <!-- Mode 2: Natural Language -->
      <div v-if="lookbookStore.triggerMode === 'nl'" class="space-y-3 pt-2 border-t border-oat-200">
        <div class="flex flex-col sm:flex-row gap-2.5">
          <input
            v-model="lookbookStore.nlQuery"
            @keyup.enter="generateNLLook"
            type="text"
            placeholder="例如：明天去互联网公司面试，想要精神但别太死板的搭配..."
            class="flex-1 px-4 py-2.5 rounded-2xl bg-oat-50 border border-oat-300 focus:border-charcoal focus:bg-white outline-none text-[13.5px]"
          />
          <button
            @click="generateNLLook"
            :disabled="lookbookStore.loading"
            class="px-5 py-2.5 rounded-2xl bg-charcoal hover:bg-charcoal-hover text-white text-[13px] font-medium transition shrink-0"
          >
            {{ lookbookStore.loading ? '生成中...' : '生成定制方案' }}
          </button>
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <span class="text-[12px] text-warmgray">快捷场景：</span>
          <button
            v-for="sc in QUICK_SCENARIOS"
            :key="sc.label"
            @click="applyQuickScenario(sc)"
            class="px-3 py-1 rounded-full bg-oat-100 hover:bg-terracotta-light hover:text-terracotta-dark text-[12px] text-charcoal-soft transition"
          >
            {{ sc.label }}
          </button>
        </div>
      </div>

      <!-- Mode 3: Anchor Item -->
      <div v-if="lookbookStore.triggerMode === 'anchor'" class="flex flex-wrap items-center justify-between gap-3 pt-2 border-t border-oat-200">
        <div class="flex items-center gap-3">
          <span class="text-[12px] text-warmgray">当前锁定锚点单品：</span>
          <div
            v-if="anchorItem"
            class="inline-flex items-center gap-2.5 px-3 py-1.5 rounded-2xl bg-terracotta-light/70 border border-terracotta/40"
          >
            <div class="w-7 h-7 rounded-lg bg-white p-0.5">
              <GarmentVisual :image-url="anchorItem.processedImageUrl" :svg-type="anchorItem.svgType" :color-hex="anchorItem.colorHex" />
            </div>
            <span class="text-[13px] font-semibold text-charcoal">{{ anchorItem.name }}</span>
          </div>
          <span v-else class="text-[12px] text-warmgray">请先录入衣物</span>
        </div>
        <div v-if="wardrobeStore.items.length > 0" class="flex items-center gap-2">
          <span class="text-[12px] text-warmgray">切换锚点：</span>
          <select
            :value="lookbookStore.anchorItemId"
            @change="changeAnchorById(Number(($event.target as HTMLSelectElement).value))"
            class="px-3 py-1.5 rounded-xl bg-oat-50 border border-oat-300 text-[12.5px] text-charcoal outline-none"
          >
            <option v-for="item in wardrobeStore.items" :key="item.id" :value="item.id">
              {{ item.name }} ({{ categoryLabel(item.category) }})
            </option>
          </select>
        </div>
      </div>
    </div>

    <!-- Main Split: Left Flat-Lay Canvas + Right Owned/Missing Panel -->
    <div v-if="lookbookStore.currentPlan" class="grid grid-cols-1 lg:grid-cols-12 gap-6">
      <!-- Left 7-Col Flat-Lay Canvas -->
      <div class="lg:col-span-7 bg-white rounded-3xl border border-oat-300 shadow-editorial overflow-hidden flex flex-col">
        <div class="px-6 pt-6 pb-4 flex flex-wrap items-start justify-between gap-3 border-b border-oat-200/80">
          <div>
            <div class="flex items-center gap-2 flex-wrap">
              <span class="px-2.5 py-0.5 rounded-full bg-oat-100 border border-oat-300 text-[11px] font-medium text-charcoal-soft">
                {{ lookbookStore.currentPlan.sceneTag }}
              </span>
              <span class="font-mono text-[11.5px] text-terracotta font-semibold">
                综合匹配度 {{ lookbookStore.currentPlan.matchScore }}%
              </span>
              <span
                v-if="lookbookStore.currentPlan.usedRealLlm"
                class="px-2 py-0.5 rounded-full bg-emerald-50 text-emerald-700 border border-emerald-200 text-[10.5px] font-mono"
              >
                LLM 精排
              </span>
            </div>
            <h3 class="font-serif text-2xl font-semibold text-charcoal mt-1.5">
              {{ lookbookStore.currentPlan.themeTitle }}
            </h3>
          </div>
          <div class="flex flex-wrap items-center gap-2">
            <button
              type="button"
              @click="handleDislikePlan"
              :disabled="lookbookStore.loading"
              title="不太喜欢这套，降低该风格权重并立即换一套"
              class="inline-flex items-center gap-1 px-3 py-2 rounded-full border border-oat-300 bg-white hover:border-black text-[12px] font-medium text-[#555555] hover:text-black transition disabled:opacity-50"
            >
              <AppIcon name="refresh" :size="13" />
              不太喜欢
            </button>
            <button
              @click="handleToggleFavorite"
              class="inline-flex items-center gap-1.5 px-3.5 py-2 rounded-full border text-[12.5px] font-medium transition"
              :class="
                lookbookStore.currentPlan.favorite
                  ? 'bg-terracotta-light border-terracotta text-terracotta-dark'
                  : 'bg-white border-oat-300 text-charcoal hover:bg-oat-100'
              "
            >
              <AppIcon name="heart" :size="15" :fill="lookbookStore.currentPlan.favorite ? '#000000' : 'none'" />
              {{ lookbookStore.currentPlan.favorite ? '已收藏' : '收藏方案' }}
            </button>
            <button
              @click="handleRecordToday"
              class="inline-flex items-center gap-1.5 px-4 py-2 rounded-full bg-charcoal hover:bg-charcoal-hover text-white text-[12.5px] font-medium shadow-sm transition"
            >
              <AppIcon name="calendar" :size="14" />
              记录为今日穿搭
            </button>
          </div>
        </div>

        <div class="p-6 flatlay-canvas flex-1">
          <!-- Alta 3-Mode Stage Switcher: 网格平铺 / 自由拼贴 / AI 上身预览 -->
          <div class="flex flex-wrap items-center justify-between gap-2.5 mb-4">
            <div class="inline-flex p-1 rounded-full bg-white border border-black/10 shadow-sm">
              <button
                type="button"
                @click="canvasMode = 'grid'"
                class="px-3.5 py-1 rounded-full text-[12px] font-medium transition flex items-center gap-1.5"
                :class="canvasMode === 'grid' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
              >
                <AppIcon name="layers" :size="13" />
                网格平铺
              </button>
              <button
                type="button"
                @click="canvasMode = 'collage'"
                class="px-3.5 py-1 rounded-full text-[12px] font-medium transition flex items-center gap-1.5"
                :class="canvasMode === 'collage' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
              >
                <AppIcon name="move" :size="13" />
                自由拼贴
              </button>
              <button
                type="button"
                @click="canvasMode = 'tryon'"
                class="px-3.5 py-1 rounded-full text-[12px] font-medium transition flex items-center gap-1.5"
                :class="canvasMode === 'tryon' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
              >
                <span
                  v-if="lookbookStore.activeTryOnJobs.length > 0"
                  class="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse"
                />
                <AppIcon v-else name="sparkles" :size="13" />
                AI 上身预览
              </button>
            </div>
            <span class="font-mono text-[11.5px] text-warmgray">{{ lookbookStore.currentPlan.slots.length }} PIECES</span>
          </div>

          <!-- Mode 1: Classic Flat-Lay Grid -->
          <template v-if="canvasMode === 'grid'">
            <div class="grid grid-cols-2 sm:grid-cols-3 gap-4">
              <div
                v-for="(slot, idx) in lookbookStore.currentPlan.slots"
                :key="`${slot.slotKey}-${slot.item.id}-${idx}`"
                class="relative bg-white/95 rounded-2xl border p-3.5 shadow-card hover:shadow-editorial transition-all duration-300 group flex flex-col justify-between"
                :class="[
                  slot.missing ? 'border-terracotta/60 border-dashed' : 'border-oat-300/90',
                  idx === 0 ? 'sm:row-span-2' : '',
                ]"
              >
                <div class="flex items-center justify-between gap-1 z-10">
                  <span
                    class="text-[10.5px] font-medium px-2 py-0.5 rounded-full"
                    :class="
                      slot.missing
                        ? 'bg-terracotta-light text-terracotta-dark'
                        : slot.anchor
                          ? 'bg-charcoal text-white'
                          : 'bg-oat-100 text-warmgray'
                    "
                  >
                    {{ slot.anchor ? '★ 核心锚点' : slot.missing ? '建议购入' : slot.slotLabel }}
                  </span>
                  <button
                    v-if="!slot.anchor"
                    @click="handleSwapSlot(slot.slotKey, slot.item.id)"
                    title="局部换一件同品类单品"
                    class="w-7 h-7 rounded-full bg-oat-100 hover:bg-charcoal hover:text-white text-charcoal-soft flex items-center justify-center transition active:rotate-180 duration-300"
                  >
                    <AppIcon name="refresh" :size="13" />
                  </button>
                </div>
                <div class="my-2 flex-1 flex items-center justify-center min-h-[105px]">
                  <GarmentVisual
                    :image-url="slot.item.processedImageUrl"
                    :svg-type="slot.item.svgType"
                    :color-hex="slot.item.colorHex"
                    :alt="slot.item.name"
                    class="max-h-[180px] transition-transform duration-300 group-hover:scale-105"
                  />
                </div>
                <div class="pt-2 border-t border-oat-100">
                  <div class="text-[12.5px] font-semibold text-charcoal truncate">{{ slot.item.name }}</div>
                  <div class="flex items-center justify-between mt-0.5 text-[11px]">
                    <span class="text-warmgray">{{ slot.item.color }}</span>
                    <span v-if="!slot.missing" class="text-emerald-700 font-medium">已拥有</span>
                    <span v-else class="text-terracotta font-medium">缺品槽位</span>
                  </div>
                </div>
              </div>
            </div>
          </template>

          <!-- Mode 2: Free-Form Flat-Lay Collage Canvas (Whering / Shoplook style) -->
          <FlatLayCollageCanvas
            v-else-if="canvasMode === 'collage'"
            :plan="lookbookStore.currentPlan"
            :saving="savingLayout"
            @save-layout="handleSaveCollageLayout"
            @swap-slot="handleSwapSlot"
          />

          <!-- Mode 3: AI Modeled Editorial Try-On Preview (tandpfun/wardrobe style) -->
          <TryOnPreviewPanel
            v-else
            :plan="lookbookStore.currentPlan"
          />

          <div class="mt-5 p-4 rounded-2xl bg-white/90 border border-oat-300/80">
            <div class="flex items-center gap-1.5 text-[12px] font-semibold text-terracotta-dark mb-1">
              <AppIcon name="sparkles" :size="14" />
              AI 造型师搭配逻辑
            </div>
            <p class="text-[13px] text-charcoal-soft leading-relaxed">{{ lookbookStore.currentPlan.aiReasoning }}</p>
          </div>
        </div>

        <!-- Multi-Turn Refinement Bar -->
        <div class="p-4 bg-oat-50 border-t border-oat-200 space-y-2.5">
          <div class="flex flex-wrap items-center gap-1.5">
            <span class="text-[11.5px] text-warmgray mr-1">多轮微调：</span>
            <button
              v-for="chip in REFINE_CHIPS"
              :key="chip"
              @click="applyRefinement(chip)"
              class="px-2.5 py-1 rounded-full bg-white hover:bg-terracotta-light border border-oat-300 hover:border-terracotta/50 text-[11.5px] text-charcoal transition"
            >
              {{ chip }}
            </button>
          </div>
          <div class="flex gap-2">
            <input
              v-model="refineInput"
              @keyup.enter="applyRefinement(refineInput)"
              type="text"
              placeholder="继续对话微调搭配，如：把裤子换成黑色阔腿裤 / 换一双更休闲的鞋..."
              class="flex-1 px-3.5 py-2 rounded-xl bg-white border border-oat-300 focus:border-charcoal outline-none text-[13px]"
            />
            <button
              @click="applyRefinement(refineInput)"
              :disabled="lookbookStore.loading"
              class="px-4 py-2 rounded-xl bg-charcoal hover:bg-charcoal-hover text-white text-[12.5px] font-medium transition"
            >
              微调
            </button>
          </div>
        </div>
      </div>

      <!-- Right 5-Col Reuse & Commerce Panel -->
      <div class="lg:col-span-5 flex flex-col gap-5">
        <div class="bg-white rounded-3xl border border-oat-300 shadow-card p-5">
          <div class="flex items-center justify-between mb-3.5">
            <h4 class="font-serif text-[16px] font-semibold text-charcoal">衣橱里可以这样搭</h4>
            <span class="text-[11.5px] font-mono px-2.5 py-0.5 rounded-full bg-emerald-50 text-emerald-700 border border-emerald-200">
              复用 {{ ownedSlots.length }} 件已有衣物
            </span>
          </div>
          <div v-if="ownedSlots.length > 0" class="space-y-2.5">
            <div
              v-for="slot in ownedSlots"
              :key="slot.item.id"
              @click="wardrobeStore.drawerItem = slot.item"
              class="flex items-center justify-between gap-3 p-2.5 rounded-2xl bg-oat-50 hover:bg-oat-100 border border-oat-200/80 cursor-pointer transition"
            >
              <div class="flex items-center gap-3 min-w-0">
                <div class="w-11 h-11 rounded-xl bg-white border border-oat-200 p-1 shrink-0">
                  <GarmentVisual
                    :image-url="slot.item.processedImageUrl"
                    :svg-type="slot.item.svgType"
                    :color-hex="slot.item.colorHex"
                    :alt="slot.item.name"
                  />
                </div>
                <div class="min-w-0">
                  <div class="text-[13px] font-semibold text-charcoal truncate">{{ slot.item.name }}</div>
                  <div class="text-[11.5px] text-warmgray">{{ slot.slotLabel }} · {{ slot.item.color }}</div>
                </div>
              </div>
              <span class="shrink-0 px-2.5 py-0.5 rounded-full bg-emerald-100/80 text-emerald-800 text-[11px] font-medium">
                已拥有
              </span>
            </div>
          </div>
          <div v-else class="py-6 text-center text-[12.5px] text-warmgray bg-oat-50 rounded-2xl">
            当前账号暂无可复用的已有衣物，以下为您提供导购补充推荐
          </div>
        </div>

        <div class="bg-white rounded-3xl border border-oat-300 shadow-card p-5 flex-1 flex flex-col justify-between">
          <div>
            <div class="flex items-center justify-between mb-3">
              <div>
                <h4 class="font-serif text-[16px] font-semibold text-charcoal">缺品补全 · 预算内相似商品</h4>
                <p class="text-[11.5px] text-warmgray mt-0.5">
                  当前筛选条件：单品价格 ≤ <span class="font-mono font-semibold text-charcoal">¥{{ lookbookStore.budget }}</span>
                </p>
              </div>
              <span class="w-8 h-8 rounded-xl bg-terracotta-light text-terracotta-dark flex items-center justify-center">
                <AppIcon name="shopping-bag" :size="16" />
              </span>
            </div>

            <div
              v-if="missingSlot"
              class="p-3 rounded-2xl bg-terracotta-light/55 border border-terracotta/30 mb-3.5 text-[12px] text-terracotta-dark"
            >
              <strong>缺品建议：</strong>你的衣橱缺少一件「{{ missingSlot.item.name }}」，补齐后可将本套搭配适配度提升至
              <span class="font-mono font-semibold">{{ lookbookStore.currentPlan.matchScore }}%</span>。
            </div>

            <div v-if="filteredProducts.length > 0" class="space-y-2.5">
              <div
                v-for="prod in filteredProducts"
                :key="prod.id"
                class="p-3 rounded-2xl bg-oat-50 hover:bg-white border border-oat-200 hover:border-terracotta/60 transition flex items-center gap-3"
              >
                <div class="w-14 h-14 rounded-xl bg-white border border-oat-200 p-1.5 shrink-0">
                  <GarmentVisual :image-url="prod.imageUrl" :svg-type="prod.svgType" :color-hex="prod.colorHex" :alt="prod.title" />
                </div>
                <div class="min-w-0 flex-1">
                  <div class="flex items-center gap-1.5">
                    <span class="text-[10px] px-1.5 py-0.5 rounded font-medium shrink-0" :class="platformBadgeClass(prod.platform)">
                      {{ prod.platformLabel }}
                    </span>
                    <h5 class="text-[13px] font-semibold text-charcoal truncate">{{ prod.title }}</h5>
                  </div>
                  <p class="text-[11.5px] text-warmgray mt-0.5 line-clamp-1">{{ prod.description }}</p>
                  <div class="flex items-center justify-between mt-1.5">
                    <span class="font-mono text-[14px] font-semibold text-terracotta">¥{{ prod.price }}</span>
                    <button
                      @click="wardrobeStore.activeProductModal = prod"
                      class="px-3 py-1 rounded-full bg-charcoal hover:bg-charcoal-hover text-white text-[11px] font-medium transition"
                    >
                      查看同款外链 ↗
                    </button>
                  </div>
                </div>
              </div>
            </div>

            <div v-else class="py-8 text-center rounded-2xl bg-oat-50 border border-dashed border-oat-300">
              <p class="text-[13px] text-charcoal font-medium">预算 ¥{{ lookbookStore.budget }} 内暂无匹配商品</p>
              <p class="text-[12px] text-warmgray mt-1">可将上方预算调高至 ¥280 以上查看更多推荐</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
