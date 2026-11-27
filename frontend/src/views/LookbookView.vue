<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useLookbookStore } from '@/stores/lookbook';
import { useToastStore } from '@/stores/toast';
import {
  CATEGORY_OPTIONS,
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
  return 'collage';
}

const refineInput = ref('');
const canvasMode = ref<'grid' | 'collage' | 'tryon'>(resolveInitialMode());
const sideTab = ref<'pieces' | 'shop'>('pieces');
const settingsOpen = ref(false);
const savingLayout = ref(false);
const activeRefineCmd = ref<string | null>(null);
const swappingSlotKey = ref<string | null>(null);
const togglingFavorite = ref(false);
const recordingToday = ref(false);
const togglingReviveIdle = ref(false);
const activeScenarioLabel = ref<string | null>(null);
const activeTriggerBusy = ref<'weather' | 'nl' | 'anchor' | null>(null);
const weatherBusyKey = ref<string | null>(null);
const removingItemId = ref<number | null>(null);
const addItemModalOpen = ref(false);
const addItemCategory = ref('all');
const addItemQuery = ref('');
const addingItemId = ref<number | null>(null);

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
  if (mode === 'collage' && route.query.studio === '1') {
    nextQuery.studio = '1';
  }
  if (
    route.query.mode !== nextQuery.mode ||
    route.query.planId !== nextQuery.planId ||
    (route.query.studio || undefined) !== nextQuery.studio
  ) {
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

const allSlots = computed(() => lookbookStore.currentPlan?.slots || []);
const missingSlot = computed(() => allSlots.value.find((s) => s.missing) || null);
const ownedSlotCount = computed(
  () => allSlots.value.filter((s) => !s.missing && s.item?.id > 0).length
);
const planItemIdSet = computed(
  () => new Set(allSlots.value.filter((s) => !s.missing && s.item?.id > 0).map((s) => s.item.id))
);
const pickerWardrobeItems = computed(() => {
  const kw = addItemQuery.value.trim().toLowerCase();
  return wardrobeStore.items.filter((item) => {
    if (addItemCategory.value !== 'all' && item.category !== addItemCategory.value) {
      return false;
    }
    if (!kw) return true;
    const hay = [item.name, item.color, item.subCategory, ...(item.styleTags || [])]
      .filter(Boolean)
      .join(' ')
      .toLowerCase();
    return hay.includes(kw);
  });
});

const filteredProducts = computed(() => {
  const cap = Number(lookbookStore.budget) || 300;
  const planProds = lookbookStore.currentPlan?.recommendedProducts || [];
  const inBudget = planProds.filter((p) => Number(p.price) <= cap);
  if (inBudget.length > 0) return inBudget;
  return wardrobeStore.products.filter((p) => Number(p.price) <= cap).slice(0, 3);
});

const matchLevel = computed(() => {
  const s = Number(lookbookStore.currentPlan?.matchScore ?? 92);
  if (s >= 94) return { bulbs: 3, label: '今日绝配' };
  if (s >= 88) return { bulbs: 2, label: '默契推荐' };
  return { bulbs: 1, label: '随性可穿' };
});

const hasActiveCustomSetting = computed(
  () => lookbookStore.reviveIdle || Number(lookbookStore.budget) !== 300
);

function setTriggerMode(mode: 'weather' | 'nl' | 'anchor') {
  lookbookStore.triggerMode = mode;
  if (mode === 'anchor' && !lookbookStore.anchorItemId && wardrobeStore.items.length > 0) {
    lookbookStore.anchorItemId = wardrobeStore.items[0].id;
  }
}

async function selectLiveWeather() {
  if (lookbookStore.loading || weatherBusyKey.value) return;
  weatherBusyKey.value = 'live';
  try {
    await wardrobeStore.useLiveWeather(false);
    const w = wardrobeStore.activeWeather;
    toast.show(`已选择「${w.city}」实时天气 (${w.condition} ${w.temp}°C)，点击「按此天气生成搭配」生效`);
  } catch (e: any) {
    toast.show(e?.message || '获取实时天气失败');
  } finally {
    weatherBusyKey.value = null;
  }
}

function selectWeatherPreset(idx: number) {
  if (lookbookStore.loading || weatherBusyKey.value) return;
  wardrobeStore.selectWeatherPreset(idx);
  toast.show(`已切换模拟天气：${WEATHER_PRESETS[idx].label} (${WEATHER_PRESETS[idx].temp}°C)，点击「按此天气生成搭配」生效`);
}

async function generateWeatherLook() {
  if (lookbookStore.loading) return;
  lookbookStore.triggerMode = 'weather';
  activeTriggerBusy.value = 'weather';
  try {
    await lookbookStore.generateRecommendation({
      triggerType: 'daily_weather',
      anchorItemId: null,
    });
    const w = wardrobeStore.activeWeather;
    toast.show(`已按「${w.city} ${w.condition} ${w.temp}°C」生成今日穿搭`);
  } catch (e: any) {
    toast.show(e?.message || '按天气生成搭配失败');
  } finally {
    activeTriggerBusy.value = null;
  }
}

async function generateNLLook() {
  if (lookbookStore.loading) return;
  lookbookStore.triggerMode = 'nl';
  activeTriggerBusy.value = 'nl';
  toast.show('正在按场景需求定制穿搭方案…');
  try {
    await lookbookStore.generateRecommendation({
      triggerType: 'nl_query',
      userQuery: lookbookStore.nlQuery,
      anchorItemId: null,
    });
    toast.show('已按你的场景要求生成专属穿搭');
  } catch (e: any) {
    toast.show(e?.message || '生成搭配失败，请重试');
  } finally {
    activeTriggerBusy.value = null;
  }
}

async function applyQuickScenario(sc: (typeof QUICK_SCENARIOS)[number]) {
  if (lookbookStore.loading) return;
  lookbookStore.nlQuery = sc.query;
  activeScenarioLabel.value = sc.label;
  try {
    await generateNLLook();
  } finally {
    activeScenarioLabel.value = null;
  }
}

function selectAnchorDraft(id: number) {
  if (lookbookStore.loading) return;
  lookbookStore.anchorItemId = id;
}

async function generateAnchorLook() {
  if (lookbookStore.loading || !anchorItem.value) return;
  lookbookStore.triggerMode = 'anchor';
  lookbookStore.anchorItemId = anchorItem.value.id;
  activeTriggerBusy.value = 'anchor';
  try {
    await lookbookStore.generateRecommendation({
      triggerType: 'anchor_item',
      anchorItemId: anchorItem.value.id,
    });
    toast.show(`已围绕「${anchorItem.value.name}」生成全套搭配`);
  } catch (e: any) {
    toast.show(e?.message || '生成单品核心搭配失败');
  } finally {
    activeTriggerBusy.value = null;
  }
}

function handleToggleSlotLock(item: { id: number; name: string }) {
  if (!item?.id || item.id <= 0) return;
  const locked = lookbookStore.toggleSlotLock(item.id);
  toast.show(
    locked
      ? `已锁定「${item.name}」（一句话微调时优先保留）`
      : `已取消锁定「${item.name}」，现在可随时点「换一件」替换`
  );
}

async function handleSwapSlot(slotKey: string, currentItemId: number) {
  if (swappingSlotKey.value || lookbookStore.loading) return;
  swappingSlotKey.value = slotKey;
  try {
    await lookbookStore.swapSlot(slotKey, currentItemId > 0 ? [currentItemId] : []);
    toast.show('已完成单品局部替换');
  } catch (e: any) {
    toast.show(e?.message || '暂无更多同品类单品可替换');
  } finally {
    swappingSlotKey.value = null;
  }
}

async function handleRemoveSlot(itemId: number, itemName?: string) {
  if (!itemId || itemId <= 0 || removingItemId.value !== null || lookbookStore.loading) return;
  if (ownedSlotCount.value <= 1) {
    toast.show('搭配至少需保留 1 件衣橱单品');
    return;
  }
  removingItemId.value = itemId;
  try {
    await lookbookStore.removePlanItem(itemId);
    toast.show(itemName ? `已从本套移除「${itemName}」` : '已从本套移除该单品');
  } catch (e: any) {
    toast.show(e?.message || '移除单品失败');
  } finally {
    removingItemId.value = null;
  }
}

function openAddItemModal() {
  addItemCategory.value = 'all';
  addItemQuery.value = '';
  addItemModalOpen.value = true;
}

async function handleAddPlanItem(itemId: number, itemName: string) {
  if (!itemId || addingItemId.value !== null || lookbookStore.loading) return;
  addingItemId.value = itemId;
  try {
    await lookbookStore.addPlanItem(itemId);
    toast.show(`已加入「${itemName}」`);
    addItemModalOpen.value = false;
  } catch (e: any) {
    toast.show(e?.message || '添加单品失败');
  } finally {
    addingItemId.value = null;
  }
}

async function handleSaveCollageLayout(nodes: CollageNode[]) {
  savingLayout.value = true;
  try {
    await lookbookStore.saveCollageLayout(nodes);
    toast.show('画板排版已保存');
  } catch (e: any) {
    toast.show(e?.message || '保存排版失败');
  } finally {
    savingLayout.value = false;
  }
}

async function applyRefinement(text: string) {
  const cmd = (text || '').trim();
  if (!cmd || lookbookStore.loading || activeRefineCmd.value) return;
  activeRefineCmd.value = cmd;
  refineInput.value = '';
  toast.show(`正在微调：「${cmd}」…`);
  if (cmd.includes('200') && cmd.includes('预算')) {
    lookbookStore.budget = 200;
  }
  try {
    await lookbookStore.generateRecommendation({
      refineInstruction: cmd,
      basePlanId: lookbookStore.currentPlan?.id,
    });
    toast.show(`微调完成：${cmd}`);
  } catch (e: any) {
    toast.show(e?.message || '微调失败，请重试');
  } finally {
    activeRefineCmd.value = null;
  }
}

async function handleToggleFavorite() {
  if (togglingFavorite.value) return;
  togglingFavorite.value = true;
  try {
    const updated = await lookbookStore.toggleFavorite();
    if (updated?.favorite) {
      toast.show('已收藏该穿搭方案', '去收藏馆', () => {
        router.push({ path: '/calendar', query: { tab: 'favorites' } });
      });
    } else {
      toast.show('已取消收藏');
    }
  } catch (e: any) {
    toast.show(e?.message || '收藏状态更新失败');
  } finally {
    togglingFavorite.value = false;
  }
}

async function handleDislikePlan() {
  if (lookbookStore.loading) return;
  try {
    await lookbookStore.dislikePlan();
    toast.show('已为你换了一套搭配方案');
  } catch (e: any) {
    toast.show(e?.message || '换搭失败，请重试');
  }
}

function handleToggleReviveIdle() {
  if (lookbookStore.loading || togglingReviveIdle.value) return;
  lookbookStore.reviveIdle = !lookbookStore.reviveIdle;
  toast.show(
    lookbookStore.reviveIdle
      ? '已开启「盘活闲置」，点击「生成搭配」将优先搭少穿单品'
      : '已关闭盘活闲置模式'
  );
}

async function handleRecordToday() {
  if (recordingToday.value) return;
  recordingToday.value = true;
  try {
    await lookbookStore.recordToday();
    toast.show('已记录为今日穿搭，并同步更新单品穿着次数', '查看日历', () => {
      router.push('/calendar');
    });
  } catch (e: any) {
    toast.show(e?.message || '记录今日穿搭失败');
  } finally {
    recordingToday.value = false;
  }
}
</script>

<template>
  <section class="space-y-4">
    <!-- Slim Single-Row Command Strip (Progressive Disclosure for Weather Sim / Idle / Budget) -->
    <div class="bg-white rounded-2xl border border-black/[0.08] px-3.5 py-2.5 space-y-3">
      <div class="flex flex-wrap items-center justify-between gap-2.5">
        <div class="inline-flex p-0.5 rounded-full bg-[#F5F5F5] gap-0.5">
          <button
            type="button"
            @click="setTriggerMode('weather')"
            :disabled="lookbookStore.loading"
            class="px-3.5 py-1.5 rounded-full text-[12.5px] font-medium transition inline-flex items-center gap-1.5 disabled:opacity-60 disabled:cursor-not-allowed"
            :class="lookbookStore.triggerMode === 'weather' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
          >
            <AppIcon v-if="activeTriggerBusy === 'weather'" name="loader" :size="14" class="animate-spin" />
            <AppIcon v-else name="cloud-sun" :size="14" />
            按天气
          </button>
          <button
            type="button"
            @click="setTriggerMode('nl')"
            :disabled="lookbookStore.loading"
            class="px-3.5 py-1.5 rounded-full text-[12.5px] font-medium transition inline-flex items-center gap-1.5 disabled:opacity-60 disabled:cursor-not-allowed"
            :class="lookbookStore.triggerMode === 'nl' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
          >
            <AppIcon v-if="activeTriggerBusy === 'nl'" name="loader" :size="14" class="animate-spin" />
            <AppIcon v-else name="message" :size="14" />
            说场景
          </button>
          <button
            type="button"
            @click="setTriggerMode('anchor')"
            :disabled="lookbookStore.loading"
            class="px-3.5 py-1.5 rounded-full text-[12.5px] font-medium transition inline-flex items-center gap-1.5 disabled:opacity-60 disabled:cursor-not-allowed"
            :class="lookbookStore.triggerMode === 'anchor' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
          >
            <AppIcon v-if="activeTriggerBusy === 'anchor'" name="loader" :size="14" class="animate-spin" />
            <AppIcon v-else name="pin" :size="14" />
            单品搭
          </button>
        </div>

        <button
          type="button"
          @click="settingsOpen = !settingsOpen"
          class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full border text-[12px] font-medium transition"
          :class="
            settingsOpen || hasActiveCustomSetting
              ? 'bg-black text-white border-black'
              : 'bg-[#FAFAFA] text-[#444444] border-black/[0.08] hover:border-black/30 hover:text-black'
          "
        >
          <AppIcon name="sliders" :size="13" />
          <span>偏好与模拟</span>
          <span
            v-if="hasActiveCustomSetting"
            class="w-1.5 h-1.5 rounded-full bg-emerald-400"
          />
        </button>
      </div>

      <!-- Mode 1: Weather Confirmation Strip (User picks real/simulated weather, then explicitly generates) -->
      <div v-if="lookbookStore.triggerMode === 'weather'" class="pt-2.5 border-t border-black/[0.06] flex flex-wrap items-center justify-between gap-2.5">
        <div class="flex flex-wrap items-center gap-1.5 text-[12px]">
          <span class="text-[#666666] mr-0.5">当前选用天气</span>
          <button
            type="button"
            @click="selectLiveWeather"
            :disabled="lookbookStore.loading || weatherBusyKey !== null"
            class="px-3 py-1 rounded-full font-medium border transition inline-flex items-center gap-1.5 disabled:opacity-55 disabled:cursor-not-allowed"
            :class="
              wardrobeStore.usingLiveWeather
                ? 'bg-black border-black text-white'
                : 'bg-[#FAFAFA] border-black/[0.08] text-[#333333] hover:border-black/30'
            "
          >
            <AppIcon v-if="weatherBusyKey === 'live'" name="loader" :size="12" class="animate-spin" />
            <span v-else class="w-1.5 h-1.5 rounded-full bg-emerald-400" />
            {{ wardrobeStore.liveWeather.city }} 实时 {{ wardrobeStore.liveWeather.condition }} {{ wardrobeStore.liveWeather.temp }}°C
          </button>
          <button
            v-for="(w, idx) in WEATHER_PRESETS"
            :key="w.id"
            type="button"
            @click="selectWeatherPreset(idx)"
            :disabled="lookbookStore.loading || weatherBusyKey !== null"
            class="px-2.5 py-1 rounded-full font-medium border transition inline-flex items-center gap-1 disabled:opacity-55 disabled:cursor-not-allowed"
            :class="
              !wardrobeStore.usingLiveWeather && wardrobeStore.weatherPresetIdx === idx
                ? 'bg-black border-black text-white'
                : 'bg-[#FAFAFA] border-black/[0.08] text-[#555555] hover:border-black/30'
            "
          >
            <AppIcon :name="w.icon" :size="12" />
            {{ w.label }} <span class="font-mono">{{ w.temp }}°C</span>
          </button>
        </div>

        <button
          type="button"
          @click="generateWeatherLook"
          :disabled="lookbookStore.loading"
          class="px-5 py-2 rounded-full bg-black hover:bg-[#262626] text-white text-[12.5px] font-medium transition shrink-0 inline-flex items-center justify-center gap-1.5 disabled:opacity-55 disabled:cursor-not-allowed"
        >
          <AppIcon v-if="activeTriggerBusy === 'weather'" name="loader" :size="13" class="animate-spin" />
          <AppIcon v-else name="sparkles" :size="13" />
          {{ activeTriggerBusy === 'weather' ? '生成中…' : '按此天气生成搭配' }}
        </button>
      </div>

      <!-- Mode 2: Natural Language Input (Only shown when selected) -->
      <div v-if="lookbookStore.triggerMode === 'nl'" class="space-y-2 pt-2.5 border-t border-black/[0.06]">
        <div class="flex flex-col sm:flex-row gap-2">
          <input
            v-model="lookbookStore.nlQuery"
            @keyup.enter="generateNLLook"
            :disabled="lookbookStore.loading"
            type="text"
            placeholder="例如：明天去互联网公司面试，想要精神但别太死板的搭配..."
            class="flex-1 px-4 py-2 rounded-full bg-[#FAFAFA] border border-black/[0.08] focus:border-black focus:bg-white outline-none text-[13px] disabled:opacity-60"
          />
          <button
            type="button"
            @click="generateNLLook"
            :disabled="lookbookStore.loading"
            class="px-5 py-2 rounded-full bg-black hover:bg-[#262626] text-white text-[12.5px] font-medium transition shrink-0 inline-flex items-center justify-center gap-1.5 disabled:opacity-55 disabled:cursor-not-allowed"
          >
            <AppIcon v-if="lookbookStore.loading" name="loader" :size="13" class="animate-spin" />
            {{ lookbookStore.loading ? '生成中…' : '生成搭配' }}
          </button>
        </div>
        <div class="flex flex-wrap items-center gap-1.5">
          <button
            v-for="sc in QUICK_SCENARIOS"
            :key="sc.label"
            type="button"
            @click="applyQuickScenario(sc)"
            :disabled="lookbookStore.loading"
            class="px-3 py-1 rounded-full text-[11.5px] transition inline-flex items-center gap-1 disabled:opacity-50 disabled:cursor-not-allowed"
            :class="
              activeScenarioLabel === sc.label
                ? 'bg-black text-white'
                : 'bg-[#F5F5F5] hover:bg-black hover:text-white text-[#444444]'
            "
          >
            <AppIcon v-if="activeScenarioLabel === sc.label" name="loader" :size="11" class="animate-spin" />
            {{ sc.label }}
          </button>
        </div>
      </div>

      <!-- Mode 3: Anchor Item Selector (Pick item first, then explicitly generate) -->
      <div v-if="lookbookStore.triggerMode === 'anchor'" class="flex flex-wrap items-center justify-between gap-2.5 pt-2.5 border-t border-black/[0.06]">
        <div class="flex flex-wrap items-center gap-2.5">
          <span class="text-[12px] text-[#666666]">围绕核心单品</span>
          <div
            v-if="anchorItem"
            class="inline-flex items-center gap-2 px-2.5 py-1 rounded-full bg-[#F5F5F5] border border-black/15"
          >
            <div class="w-6 h-6 rounded-full bg-white p-0.5 overflow-hidden">
              <GarmentVisual :image-url="anchorItem.processedImageUrl" :svg-type="anchorItem.svgType" :color-hex="anchorItem.colorHex" />
            </div>
            <span class="text-[12.5px] font-medium text-black">{{ anchorItem.name }}</span>
          </div>
          <span v-else class="text-[12px] text-[#666666]">请先录入衣物</span>
          <select
            v-if="wardrobeStore.items.length > 0"
            :value="anchorItem?.id || ''"
            :disabled="lookbookStore.loading"
            @change="selectAnchorDraft(Number(($event.target as HTMLSelectElement).value))"
            class="px-3 py-1.5 rounded-full bg-[#FAFAFA] border border-black/[0.08] text-[12px] text-black outline-none disabled:opacity-55"
          >
            <option v-for="item in wardrobeStore.items" :key="item.id" :value="item.id">
              {{ item.name }} ({{ categoryLabel(item.category) }})
            </option>
          </select>
        </div>

        <button
          type="button"
          @click="generateAnchorLook"
          :disabled="lookbookStore.loading || !anchorItem"
          class="px-5 py-2 rounded-full bg-black hover:bg-[#262626] text-white text-[12.5px] font-medium transition shrink-0 inline-flex items-center justify-center gap-1.5 disabled:opacity-55 disabled:cursor-not-allowed"
        >
          <AppIcon v-if="activeTriggerBusy === 'anchor'" name="loader" :size="13" class="animate-spin" />
          <AppIcon v-else name="pin" :size="13" />
          {{ activeTriggerBusy === 'anchor' ? '生成中…' : '围绕它生成搭配' }}
        </button>
      </div>

      <!-- Collapsible Advanced Settings (Revive Idle & Missing-Item Budget) -->
      <div v-if="settingsOpen" class="pt-3 border-t border-black/[0.06] space-y-3 text-[12px]">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <button
            type="button"
            @click="handleToggleReviveIdle"
            :disabled="lookbookStore.loading || togglingReviveIdle"
            class="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-full border font-medium transition disabled:opacity-55 disabled:cursor-not-allowed"
            :class="
              lookbookStore.reviveIdle
                ? 'bg-black text-white border-black'
                : 'bg-white text-[#444444] border-black/[0.1] hover:border-black'
            "
          >
            <AppIcon v-if="togglingReviveIdle" name="loader" :size="13" class="animate-spin" />
            <AppIcon v-else name="sparkles" :size="13" />
            {{ lookbookStore.reviveIdle ? '盘活闲置模式：已开启' : '开启「盘活闲置」优先搭少穿单品' }}
          </button>

          <div class="flex items-center gap-2">
            <span class="text-[#666666]">缺品预算 ≤</span>
            <div class="relative flex items-center">
              <span class="absolute left-2.5 font-mono text-[#666666]">¥</span>
              <input
                v-model.number="lookbookStore.budget"
                type="number"
                min="100"
                max="1000"
                step="20"
                class="w-24 pl-6 pr-2 py-1 rounded-full bg-[#FAFAFA] border border-black/[0.08] font-mono font-semibold text-black outline-none focus:border-black"
              />
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Main 2-Column Workspace: Left Pure Stage + Right Interactive Swapper & AI Refinement -->
    <div v-if="lookbookStore.currentPlan" class="grid grid-cols-1 lg:grid-cols-12 gap-5 items-start">
      <!-- Left 7-Col: Clean Primary Stage Card -->
      <div class="lg:col-span-7 bg-white rounded-3xl border border-black/[0.08] overflow-hidden">
        <!-- Stage Top Header: 3-Mode Pill + Compact Match Indicator -->
        <div class="px-4 sm:px-5 py-3.5 flex flex-wrap items-center justify-between gap-2.5 border-b border-black/[0.06]">
          <div class="inline-flex p-0.5 rounded-full bg-[#F5F5F5]">
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
              @click="canvasMode = 'grid'"
              class="px-3.5 py-1 rounded-full text-[12px] font-medium transition flex items-center gap-1.5"
              :class="canvasMode === 'grid' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
            >
              <AppIcon name="layout-grid" :size="13" />
              网格
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
              AI 试穿
            </button>
          </div>

          <div class="flex items-center gap-2" :title="`综合匹配度 ${lookbookStore.currentPlan.matchScore}%`">
            <span
              v-if="lookbookStore.currentPlan.usedVisualReview"
              class="px-2 py-0.5 rounded-full bg-black text-white text-[10px] font-mono"
            >
              VLM
            </span>
            <span class="inline-flex items-center gap-0.5">
              <AppIcon
                v-for="n in 3"
                :key="n"
                name="lightbulb"
                :size="15"
                :fill="n <= matchLevel.bulbs ? '#FBBF24' : 'none'"
                :class="n <= matchLevel.bulbs ? 'text-black' : 'text-black/20'"
              />
            </span>
            <span class="text-[12px] font-medium text-black">{{ matchLevel.label }}</span>
          </div>
        </div>

        <!-- Stage Body (Zero nested gray wrapper boxes!) -->
        <div class="p-3.5 sm:p-4">
          <FlatLayCollageCanvas
            v-if="canvasMode === 'collage'"
            :plan="lookbookStore.currentPlan"
            :saving="savingLayout"
            @save-layout="handleSaveCollageLayout"
            @swap-slot="handleSwapSlot"
            @remove-slot="handleRemoveSlot"
            @open-add-item="openAddItemModal"
          />

          <template v-else-if="canvasMode === 'grid'">
            <div class="grid grid-cols-2 sm:grid-cols-3 gap-3 p-1">
              <div
                v-for="(slot, idx) in allSlots"
                :key="`${slot.slotKey}-${slot.item.id}-${idx}`"
                class="relative bg-[#FAFAFA] rounded-2xl border p-3 hover:border-black/20 transition group flex flex-col justify-between"
                :class="[
                  slot.missing ? 'border-black/25 border-dashed' : 'border-black/[0.06]',
                  idx === 0 ? 'sm:row-span-2' : '',
                ]"
              >
                <div class="flex items-center justify-between gap-1">
                  <button
                    v-if="slot.anchor"
                    type="button"
                    @click="handleToggleSlotLock(slot.item)"
                    title="点击取消锁定，取消后可换一件"
                    class="inline-flex items-center gap-1 text-[10.5px] font-medium px-2.5 py-0.5 rounded-full bg-black text-white hover:bg-[#262626] transition"
                  >
                    <AppIcon name="pin" :size="10" />
                    已锁定 ×
                  </button>
                  <span
                    v-else
                    class="text-[10.5px] font-medium px-2 py-0.5 rounded-full"
                    :class="slot.missing ? 'bg-white border border-black/15 text-black' : 'bg-white text-[#666666]'"
                  >
                    {{ slot.missing ? '建议补全' : slot.slotLabel }}
                  </span>
                  <div v-if="!slot.missing" class="flex items-center gap-1">
                    <button
                      v-if="!slot.anchor"
                      type="button"
                      @click="handleToggleSlotLock(slot.item)"
                      title="锁定此单品，微调时保留"
                      class="w-7 h-7 rounded-full bg-white hover:bg-black hover:text-white text-[#666666] flex items-center justify-center transition shadow-xs"
                    >
                      <AppIcon name="pin" :size="11" />
                    </button>
                    <button
                      v-if="!slot.anchor"
                      type="button"
                      @click="handleSwapSlot(slot.slotKey, slot.item.id)"
                      :disabled="swappingSlotKey !== null || lookbookStore.loading"
                      title="换一件同品类"
                      class="w-7 h-7 rounded-full bg-white hover:bg-black hover:text-white text-[#333333] flex items-center justify-center transition shadow-xs disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      <AppIcon name="refresh" :size="12" :class="swappingSlotKey === slot.slotKey ? 'animate-spin' : ''" />
                    </button>
                    <button
                      type="button"
                      @click="handleRemoveSlot(slot.item.id, slot.item.name)"
                      :disabled="ownedSlotCount <= 1 || removingItemId !== null || lookbookStore.loading"
                      title="从本套移除"
                      class="w-7 h-7 rounded-full bg-white hover:bg-red-500 hover:text-white text-[#666666] flex items-center justify-center transition shadow-xs disabled:opacity-40 disabled:cursor-not-allowed"
                    >
                      <AppIcon name="trash" :size="11" />
                    </button>
                  </div>
                </div>
                <div class="my-2 flex-1 flex items-center justify-center min-h-[100px]">
                  <GarmentVisual
                    :image-url="slot.item.processedImageUrl"
                    :svg-type="slot.item.svgType"
                    :color-hex="slot.item.colorHex"
                    :alt="slot.item.name"
                    class="max-h-[175px] transition-transform duration-300 group-hover:scale-105"
                  />
                </div>
                <div class="pt-1.5 text-[12px] font-medium text-black truncate">{{ slot.item.name }}</div>
              </div>
            </div>
          </template>

          <TryOnPreviewPanel
            v-else
            :plan="lookbookStore.currentPlan"
          />
        </div>

        <!-- Clean Unified Action Footer -->
        <div class="px-4 sm:px-5 py-3.5 border-t border-black/[0.06] flex flex-wrap items-center justify-between gap-3">
          <div class="min-w-0">
            <h3 class="text-[16px] font-semibold text-black truncate">{{ lookbookStore.currentPlan.themeTitle }}</h3>
            <div class="text-[11.5px] text-[#666666] truncate mt-0.5">
              {{ lookbookStore.currentPlan.sceneTag }} · {{ allSlots.length }} 件单品
            </div>
          </div>
          <div class="flex items-center gap-2 shrink-0">
            <button
              type="button"
              @click="handleDislikePlan"
              :disabled="lookbookStore.loading"
              title="换一套方案"
              class="w-9 h-9 rounded-full border border-black/[0.1] bg-white hover:border-black text-[#444444] hover:text-black flex items-center justify-center transition disabled:opacity-50"
            >
              <AppIcon name="refresh" :size="15" :class="lookbookStore.loading ? 'animate-spin' : ''" />
            </button>
            <button
              type="button"
              @click="handleToggleFavorite"
              :disabled="togglingFavorite"
              :title="lookbookStore.currentPlan.favorite ? '取消收藏' : '收藏方案'"
              class="w-9 h-9 rounded-full border flex items-center justify-center transition disabled:opacity-50 disabled:cursor-not-allowed"
              :class="
                lookbookStore.currentPlan.favorite
                  ? 'bg-[#F2F2F2] border-black text-black'
                  : 'bg-white border-black/[0.1] text-black hover:border-black'
              "
            >
              <AppIcon v-if="togglingFavorite" name="loader" :size="15" class="animate-spin" />
              <AppIcon v-else name="heart" :size="15" :fill="lookbookStore.currentPlan.favorite ? '#000000' : 'none'" />
            </button>
            <button
              type="button"
              @click="handleRecordToday"
              :disabled="recordingToday"
              class="inline-flex items-center gap-1.5 px-4 py-2 rounded-full bg-black hover:bg-[#262626] text-white text-[12.5px] font-medium transition disabled:opacity-60 disabled:cursor-not-allowed"
            >
              <AppIcon v-if="recordingToday" name="loader" :size="14" class="animate-spin" />
              <AppIcon v-else name="calendar" :size="14" />
              {{ recordingToday ? '记录中…' : '就穿这套' }}
            </button>
          </div>
        </div>
      </div>

      <!-- Right 5-Col: Deduplicated Interactive Piece Swapper + Shopping Tabs + AI Refinement -->
      <div class="lg:col-span-5 flex flex-col gap-4">
        <!-- Tabbed Pieces / Shopping Panel -->
        <div class="bg-white rounded-3xl border border-black/[0.08] overflow-hidden">
          <div class="px-4 pt-3.5 pb-2.5 border-b border-black/[0.06] flex items-center justify-between gap-2">
            <div class="inline-flex p-0.5 rounded-full bg-[#F5F5F5]">
              <button
                type="button"
                @click="sideTab = 'pieces'"
                class="px-3.5 py-1 rounded-full text-[12px] font-medium transition"
                :class="sideTab === 'pieces' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
              >
                本套单品 ({{ allSlots.length }})
              </button>
              <button
                type="button"
                @click="sideTab = 'shop'"
                class="px-3.5 py-1 rounded-full text-[12px] font-medium transition inline-flex items-center gap-1"
                :class="sideTab === 'shop' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
              >
                <span>缺品同款</span>
                <span
                  v-if="missingSlot"
                  class="w-1.5 h-1.5 rounded-full"
                  :class="sideTab === 'shop' ? 'bg-amber-300' : 'bg-black'"
                />
              </button>
            </div>
            <span v-if="sideTab === 'shop'" class="font-mono text-[11px] text-[#666666]">
              ≤ ¥{{ lookbookStore.budget }}
            </span>
          </div>

          <!-- Tab 1: Interactive Piece Swapper -->
          <div v-if="sideTab === 'pieces'" class="p-3.5 space-y-2">
            <div
              v-for="(slot, idx) in allSlots"
              :key="`${slot.slotKey}-${slot.item.id}-${idx}`"
              @click="!slot.missing && (wardrobeStore.drawerItem = slot.item)"
              class="flex items-center justify-between gap-2.5 p-2 rounded-2xl bg-[#FAFAFA] hover:bg-[#F3F3F3] border transition"
              :class="slot.missing ? 'border-dashed border-black/20' : 'border-black/[0.05] cursor-pointer'"
            >
              <div class="flex items-center gap-2.5 min-w-0">
                <div class="w-11 h-11 rounded-xl bg-white border border-black/[0.06] p-1 shrink-0">
                  <GarmentVisual
                    :image-url="slot.item.processedImageUrl"
                    :svg-type="slot.item.svgType"
                    :color-hex="slot.item.colorHex"
                    :alt="slot.item.name"
                  />
                </div>
                <div class="min-w-0">
                  <div class="text-[13px] font-medium text-black truncate">{{ slot.item.name }}</div>
                  <div class="text-[11px] text-[#666666]">
                    {{ slot.slotLabel }} · {{ slot.missing ? '建议补全' : slot.item.color }}
                  </div>
                </div>
              </div>

              <button
                v-if="slot.missing"
                type="button"
                @click.stop="sideTab = 'shop'"
                class="shrink-0 px-2.5 py-1 rounded-full bg-black text-white text-[11px] font-medium"
              >
                看同款
              </button>
              <div v-else class="shrink-0 flex items-center gap-1">
                <button
                  v-if="slot.anchor"
                  type="button"
                  @click.stop="handleToggleSlotLock(slot.item)"
                  title="点击取消锁定，取消后可换一件"
                  class="inline-flex items-center gap-1 px-2.5 py-1 rounded-full bg-black hover:bg-[#262626] text-white text-[11px] font-medium transition"
                >
                  <AppIcon name="pin" :size="10" />
                  已锁定 ×
                </button>
                <template v-else>
                  <button
                    type="button"
                    @click.stop="handleToggleSlotLock(slot.item)"
                    title="锁定此单品，微调时保留"
                    class="w-7 h-7 rounded-full bg-white hover:bg-black hover:text-white border border-black/[0.1] text-[#666666] flex items-center justify-center transition"
                  >
                    <AppIcon name="pin" :size="11" />
                  </button>
                  <button
                    type="button"
                    @click.stop="handleSwapSlot(slot.slotKey, slot.item.id)"
                    :disabled="swappingSlotKey !== null || lookbookStore.loading"
                    title="换一件同品类"
                    class="inline-flex items-center gap-1 px-2.5 py-1 rounded-full bg-white hover:bg-black hover:text-white border border-black/[0.1] text-[11px] text-black transition disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    <AppIcon name="refresh" :size="11" :class="swappingSlotKey === slot.slotKey ? 'animate-spin' : ''" />
                    {{ swappingSlotKey === slot.slotKey ? '替换中…' : '换一件' }}
                  </button>
                </template>
                <button
                  type="button"
                  @click.stop="handleRemoveSlot(slot.item.id, slot.item.name)"
                  :disabled="ownedSlotCount <= 1 || removingItemId !== null || lookbookStore.loading"
                  title="从本套移除"
                  class="w-7 h-7 rounded-full bg-white hover:bg-red-500 hover:text-white border border-black/[0.1] text-[#666666] flex items-center justify-center transition disabled:opacity-40 disabled:cursor-not-allowed"
                >
                  <AppIcon name="trash" :size="11" />
                </button>
              </div>
            </div>

            <button
              type="button"
              @click="openAddItemModal"
              class="w-full py-2.5 rounded-2xl border border-dashed border-black/20 hover:border-black hover:bg-[#FAFAFA] text-[12px] font-medium text-black transition inline-flex items-center justify-center gap-1.5"
            >
              <AppIcon name="plus" :size="13" />
              从衣橱添加单品自由混搭
            </button>
          </div>

          <!-- Tab 2: Missing Item Commerce Dupes -->
          <div v-else class="p-3.5 space-y-2.5">
            <div
              v-if="missingSlot"
              class="px-3 py-2.5 rounded-xl bg-[#F5F5F5] text-[12px] text-black leading-relaxed"
            >
              衣橱暂缺「<strong>{{ missingSlot.item.name }}</strong>」，以下为预算 ¥{{ lookbookStore.budget }} 内精选平替：
            </div>

            <div v-if="filteredProducts.length > 0" class="space-y-2">
              <div
                v-for="prod in filteredProducts"
                :key="prod.id"
                class="p-2.5 rounded-2xl bg-[#FAFAFA] hover:bg-white border border-black/[0.06] hover:border-black/25 transition flex items-center gap-3"
              >
                <div class="w-12 h-12 rounded-xl bg-white border border-black/[0.06] p-1 shrink-0">
                  <GarmentVisual :image-url="prod.imageUrl" :svg-type="prod.svgType" :color-hex="prod.colorHex" :alt="prod.title" />
                </div>
                <div class="min-w-0 flex-1">
                  <div class="flex items-center gap-1.5">
                    <span class="text-[10px] px-1.5 py-0.5 rounded font-medium shrink-0" :class="platformBadgeClass(prod.platform)">
                      {{ prod.platformLabel }}
                    </span>
                    <h5 class="text-[12.5px] font-medium text-black truncate">{{ prod.title }}</h5>
                  </div>
                  <div class="flex items-center justify-between mt-1.5">
                    <span class="font-mono text-[13.5px] font-semibold text-black">¥{{ prod.price }}</span>
                    <button
                      type="button"
                      @click="wardrobeStore.activeProductModal = prod"
                      class="px-2.5 py-1 rounded-full bg-black hover:bg-[#262626] text-white text-[11px] font-medium transition"
                    >
                      去看看 ↗
                    </button>
                  </div>
                </div>
              </div>
            </div>

            <div v-else class="py-6 text-center rounded-2xl bg-[#FAFAFA] text-[12px] text-[#666666]">
              预算 ¥{{ lookbookStore.budget }} 内暂无匹配商品，可在顶部「偏好与模拟」调高预算
            </div>
          </div>
        </div>

        <!-- Compact AI Reasoning & Conversational Refinement Card -->
        <div class="bg-white rounded-3xl border border-black/[0.08] p-4 space-y-3">
          <div>
            <div class="flex items-center gap-1.5 text-[12px] font-semibold text-black mb-1">
              <AppIcon name="sparkles" :size="13" />
              搭配思路
            </div>
            <p class="text-[12.5px] text-[#444444] leading-relaxed">
              {{ lookbookStore.currentPlan.aiReasoning }}
            </p>
          </div>

          <div class="pt-2.5 border-t border-black/[0.06] space-y-2">
            <div class="flex flex-wrap gap-1.5">
              <button
                v-for="chip in REFINE_CHIPS"
                :key="chip"
                type="button"
                @click="applyRefinement(chip)"
                :disabled="lookbookStore.loading || activeRefineCmd !== null"
                class="px-2.5 py-1 rounded-full text-[11px] transition inline-flex items-center gap-1 disabled:opacity-50 disabled:cursor-not-allowed"
                :class="
                  activeRefineCmd === chip
                    ? 'bg-black text-white'
                    : 'bg-[#F5F5F5] hover:bg-black hover:text-white text-[#333333]'
                "
              >
                <AppIcon v-if="activeRefineCmd === chip" name="loader" :size="11" class="animate-spin" />
                {{ chip }}
              </button>
            </div>
            <div
              v-if="activeRefineCmd"
              class="flex items-center gap-1.5 px-2.5 py-1 rounded-xl bg-[#FAFAFA] border border-black/[0.06] text-[11.5px] text-[#444444]"
            >
              <AppIcon name="loader" :size="12" class="animate-spin text-black shrink-0" />
              <span class="truncate">正在微调：「{{ activeRefineCmd }}」…</span>
            </div>
            <div class="flex gap-1.5">
              <input
                v-model="refineInput"
                @keyup.enter="applyRefinement(refineInput)"
                :disabled="lookbookStore.loading || activeRefineCmd !== null"
                type="text"
                placeholder="一句话微调，如：换双更休闲的鞋…"
                class="flex-1 px-3.5 py-2 rounded-full bg-[#FAFAFA] border border-black/[0.08] focus:border-black focus:bg-white outline-none text-[12.5px] disabled:opacity-60"
              />
              <button
                type="button"
                @click="applyRefinement(refineInput)"
                :disabled="lookbookStore.loading || activeRefineCmd !== null || !refineInput.trim()"
                class="px-4 py-2 rounded-full bg-black hover:bg-[#262626] text-white text-[12px] font-medium transition shrink-0 inline-flex items-center justify-center gap-1.5 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <AppIcon v-if="lookbookStore.loading || activeRefineCmd !== null" name="loader" :size="13" class="animate-spin" />
                {{ lookbookStore.loading || activeRefineCmd !== null ? '微调中…' : '微调' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Add Wardrobe Item Picker Modal -->
    <div
      v-if="addItemModalOpen"
      @click="addItemModalOpen = false"
      class="fixed inset-0 z-[80] bg-black/45 backdrop-blur-xs flex items-end sm:items-center justify-center p-0 sm:p-4"
    >
      <div
        @click.stop
        class="w-full sm:max-w-[540px] max-h-[82vh] bg-white rounded-t-3xl sm:rounded-3xl border border-black/[0.08] shadow-2xl flex flex-col overflow-hidden"
      >
        <div class="px-5 py-4 border-b border-black/[0.06] flex items-center justify-between gap-3">
          <div>
            <h3 class="text-[15px] font-semibold text-black">从衣橱添加单品</h3>
            <p class="text-[11.5px] text-[#666666] mt-0.5">挑选任意单品加入当前搭配画板与 AI 试穿序列</p>
          </div>
          <button
            type="button"
            @click="addItemModalOpen = false"
            class="w-8 h-8 rounded-full bg-[#F5F5F5] hover:bg-black hover:text-white text-black flex items-center justify-center transition"
          >
            <AppIcon name="x" :size="15" />
          </button>
        </div>

        <div class="px-5 pt-3 pb-2.5 border-b border-black/[0.05] space-y-2.5">
          <div class="relative">
            <AppIcon name="search" :size="14" class="absolute left-3.5 top-1/2 -translate-y-1/2 text-[#888888]" />
            <input
              v-model="addItemQuery"
              type="text"
              placeholder="搜索单品名称、颜色或风格标签…"
              class="w-full pl-9 pr-3.5 py-2 rounded-full bg-[#F5F5F5] border border-transparent focus:bg-white focus:border-black outline-none text-[12.5px]"
            />
          </div>
          <div class="flex items-center gap-1.5 overflow-x-auto no-scrollbar">
            <button
              v-for="cat in [{ key: 'all', label: '全部' }, ...CATEGORY_OPTIONS]"
              :key="cat.key"
              type="button"
              @click="addItemCategory = cat.key"
              class="px-3 py-1 rounded-full text-[11.5px] font-medium shrink-0 transition"
              :class="addItemCategory === cat.key ? 'bg-black text-white' : 'bg-[#F5F5F5] text-[#555555] hover:text-black'"
            >
              {{ cat.label }}
            </button>
          </div>
        </div>

        <div class="flex-1 overflow-y-auto p-4 space-y-2">
          <div v-if="pickerWardrobeItems.length === 0" class="py-10 text-center text-[12.5px] text-[#777777]">
            没有找到匹配的衣橱单品
          </div>
          <div
            v-for="item in pickerWardrobeItems"
            :key="item.id"
            class="flex items-center justify-between gap-3 p-2.5 rounded-2xl bg-[#FAFAFA] hover:bg-[#F3F3F3] border border-black/[0.05] transition"
          >
            <div class="flex items-center gap-3 min-w-0">
              <div class="w-12 h-12 rounded-xl bg-white border border-black/[0.06] p-1 shrink-0">
                <GarmentVisual
                  :image-url="item.processedImageUrl"
                  :svg-type="item.svgType"
                  :color-hex="item.colorHex"
                  :alt="item.name"
                />
              </div>
              <div class="min-w-0">
                <div class="text-[13px] font-medium text-black truncate">{{ item.name }}</div>
                <div class="text-[11px] text-[#666666] truncate">
                  {{ categoryLabel(item.category) }} · {{ item.color }}
                </div>
              </div>
            </div>
            <span
              v-if="planItemIdSet.has(item.id)"
              class="shrink-0 px-3 py-1 rounded-full bg-[#EDEDED] text-[#666666] text-[11px] font-medium"
            >
              已在套内
            </span>
            <button
              v-else
              type="button"
              @click="handleAddPlanItem(item.id, item.name)"
              :disabled="addingItemId !== null"
              class="shrink-0 px-3.5 py-1.5 rounded-full bg-black hover:bg-[#262626] text-white text-[11.5px] font-medium transition disabled:opacity-50"
            >
              {{ addingItemId === item.id ? '加入中…' : '+ 加入' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
