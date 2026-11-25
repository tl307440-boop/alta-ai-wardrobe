<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useLookbookStore } from '@/stores/lookbook';
import { useToastStore } from '@/stores/toast';
import {
  ALL_STYLE_OPTIONS,
  CITY_OPTIONS,
  COLOR_SWATCHES,
  DISLIKED_COLOR_OPTIONS,
  categoryBarColor,
} from '@/utils/constants';
import type { OutfitPlanVo } from '@/types';
import AppIcon from '@/components/AppIcon.vue';
import AiModelConfigCard from '@/components/AiModelConfigCard.vue';
import GarmentVisual from '@/components/GarmentVisual.vue';

type TabKey = 'stats' | 'calendar' | 'settings';

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'stats', label: '衣橱统计' },
  { key: 'calendar', label: '穿搭与收藏' },
  { key: 'settings', label: '偏好与设置' },
];

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const wardrobeStore = useWardrobeStore();
const lookbookStore = useLookbookStore();
const toast = useToastStore();

/* ---------- Tabs (URL: /profile?tab=stats|calendar|settings) ---------- */
const activeTab = computed<TabKey>(() => {
  const q = route.query.tab;
  return TABS.some((t) => t.key === q) ? (q as TabKey) : 'stats';
});

function selectTab(key: TabKey) {
  if (key === activeTab.value && !route.query.panel) return;
  router.replace({ path: route.path, query: { tab: key } });
}

/* ---------- Advanced: AI model config (collapsed by default) ---------- */
const aiPanelOpen = ref(false);
const aiPanelRef = ref<HTMLElement | null>(null);

watch(
  () => [activeTab.value, route.query.panel] as const,
  async ([tab, panel]) => {
    if (tab === 'settings' && panel === 'ai') {
      aiPanelOpen.value = true;
      await nextTick();
      aiPanelRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  },
  { immediate: true }
);

const aiConfigActive = computed(
  () => wardrobeStore.aiConfig.hasApiKey && wardrobeStore.aiConfig.enabled
);

/* ---------- Stats ---------- */
const categoryStats = computed(() =>
  (wardrobeStore.preference.categoryStats || []).map((c) => ({
    ...c,
    barColor: categoryBarColor(c.key),
  }))
);

const donutSegments = computed(() => {
  const circ = 2 * Math.PI * 46;
  let offset = 0;
  return categoryStats.value.map((cat) => {
    const len = (cat.percent / 100) * circ;
    const seg = {
      ...cat,
      dashArray: `${len} ${circ - len}`,
      dashOffset: -offset,
    };
    offset += len;
    return seg;
  });
});

const sortedStyleWeights = computed(() =>
  Object.entries(wardrobeStore.preference.styleWeights || {})
    .map(([name, weight]) => ({ name, weight: Number(weight) || 0, percent: Math.round((Number(weight) || 0) * 100) }))
    .sort((a, b) => b.weight - a.weight)
    .slice(0, 6)
);

async function reviveNeglectedPiece(itemId: number, name: string) {
  lookbookStore.triggerMode = 'anchor';
  lookbookStore.anchorItemId = itemId;
  lookbookStore.reviveIdle = true;
  await lookbookStore.generateRecommendation({
    triggerType: 'anchor_item',
    anchorItemId: itemId,
    reviveIdle: true,
  });
  toast.show(`已为冷宫单品「${name}」生成专属盘活穿搭方案`);
  router.push('/lookbook');
}

/* ---------- Calendar & Favorites overview ---------- */
const monthRecordCount = computed(() => {
  const now = new Date();
  const prefix = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  return lookbookStore.history.filter((h) => h.wearDate.startsWith(prefix)).length;
});

const latestRecord = computed(
  () => [...lookbookStore.history].sort((a, b) => b.wearDate.localeCompare(a.wearDate))[0] || null
);

const recentFavorites = computed(() => lookbookStore.favorites.slice(0, 3));

function openLatestRecord() {
  if (latestRecord.value) lookbookStore.selectedCalendarDate = latestRecord.value.wearDate;
  router.push('/calendar');
}

function openFavoriteLook(plan: OutfitPlanVo) {
  lookbookStore.loadPlan(plan);
  router.push('/lookbook');
  toast.show(`已在画板加载「${plan.themeTitle}」`);
}

/* ---------- Preferences & Settings ---------- */
async function toggleStyle(st: string) {
  const cur = [...(wardrobeStore.preference.favoriteStyles || [])];
  const idx = cur.indexOf(st);
  if (idx > -1) cur.splice(idx, 1);
  else cur.push(st);
  await wardrobeStore.updatePreference({ favoriteStyles: cur });
  toast.show('已更新个人风格偏好权重');
}

async function toggleColor(field: 'favoriteColors' | 'dislikedColors', colorName: string) {
  const cur = [...(wardrobeStore.preference[field] || [])];
  const idx = cur.indexOf(colorName);
  if (idx > -1) cur.splice(idx, 1);
  else cur.push(colorName);
  await wardrobeStore.updatePreference({ [field]: cur });
}

const cityInput = ref(wardrobeStore.preference.defaultCity || '武汉');
const refreshingWeather = ref(false);

watch(
  () => wardrobeStore.preference.defaultCity,
  (c) => {
    if (c) cityInput.value = c;
  }
);

async function updateCity(city?: string) {
  const target = (city ?? cityInput.value).trim();
  if (!target) return;
  refreshingWeather.value = true;
  try {
    await wardrobeStore.updatePreference({ defaultCity: target });
    const w = await wardrobeStore.useLiveWeather(true);
    toast.show(
      w.live
        ? `已同步「${w.city}」实时天气：${w.condition} ${w.temp}°C（体感 ${w.apparentTemp ?? w.temp}°C，湿度 ${w.humidity ?? '--'}%）`
        : `已更新常驻城市为 ${target}（当前使用降级天气）`
    );
  } finally {
    refreshingWeather.value = false;
  }
}

async function refreshLiveWeather() {
  refreshingWeather.value = true;
  try {
    const w = await wardrobeStore.useLiveWeather(true);
    toast.show(
      w.live
        ? `实时天气已刷新：${w.city} ${w.condition} ${w.temp}°C（体感 ${w.apparentTemp ?? w.temp}°C，湿度 ${w.humidity ?? '--'}%）`
        : '实时气象接口暂不可达，已保持降级天气'
    );
  } finally {
    refreshingWeather.value = false;
  }
}

const BODY_SHAPES = ['H型直筒', 'X型沙漏', 'A型梨形', 'Y型倒三角', 'O型苹果'];
const COLOR_SEASONS = ['中性百搭', '春季暖亮', '夏季冷柔', '秋季暖深', '冬季冷艳'];

const heightInput = ref<number>(wardrobeStore.preference.heightCm || 165);

watch(
  () => wardrobeStore.preference.heightCm,
  (h) => {
    if (h) heightInput.value = h;
  }
);

async function selectBodyShape(shape: string) {
  await wardrobeStore.updatePreference({ bodyShape: shape });
  toast.show(`身形档案已更新为「${shape}」，搭配与试穿将自动适配对应廓形法则`);
}

async function selectColorSeason(season: string) {
  await wardrobeStore.updatePreference({ colorSeason: season });
  toast.show(`四季色彩季型已更新为「${season}」，推荐与灵感排序已同步`);
}

async function saveHeight() {
  const h = Math.max(135, Math.min(210, Number(heightInput.value) || 165));
  heightInput.value = h;
  await wardrobeStore.updatePreference({ heightCm: h });
  toast.show(`身高档案已更新为 ${h}cm`);
}

async function updateBudget(val: number) {
  await wardrobeStore.updatePreference({ budgetLimit: val });
  lookbookStore.budget = val;
  toast.show(`默认预算上限已同步为 ¥${val}`);
}

function handleLogout() {
  authStore.logout();
  wardrobeStore.reset();
  lookbookStore.reset();
  toast.show('已安全退出登录，可切换其他账号体验多用户隔离');
  router.replace('/login');
}
</script>

<template>
  <section class="space-y-6">
    <!-- 3-segment switcher -->
    <div class="flex">
      <div class="flex w-full sm:w-auto p-1 rounded-full bg-[#F5F5F5]" role="tablist" aria-label="我的">
        <button
          v-for="tab in TABS"
          :key="tab.key"
          role="tab"
          :aria-selected="activeTab === tab.key"
          @click="selectTab(tab.key)"
          class="flex-1 sm:flex-none px-5 py-2 rounded-full text-[13px] font-medium whitespace-nowrap transition"
          :class="activeTab === tab.key ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
        >
          {{ tab.label }}
        </button>
      </div>
    </div>

    <!-- ================= Tab 1: 衣橱统计 ================= -->
    <div v-if="activeTab === 'stats'" class="space-y-5">
      <!-- CPW Deep Report -->
      <div class="rounded-3xl bg-white border border-black/[0.08] p-6 space-y-5">
        <div>
          <h3 class="text-[16px] font-semibold tracking-tight text-black">衣橱资产与 CPW</h3>
          <p class="text-[12.5px] text-[#666666] mt-0.5">
            单次穿着成本 = 购入价 ÷ 穿着次数 · 已计价 {{ wardrobeStore.preference.pricedItemCount || 0 }} / {{ wardrobeStore.items.length }} 件
          </p>
        </div>

        <div class="grid grid-cols-3 gap-2.5 sm:gap-3">
          <div class="p-3.5 sm:p-4 rounded-2xl bg-[#FAFAFA]">
            <div class="font-mono text-[19px] sm:text-[24px] font-semibold text-black">
              ¥{{ Number(wardrobeStore.preference.totalWardrobeValue || 0).toLocaleString('zh-CN', { maximumFractionDigits: 0 }) }}
            </div>
            <div class="text-[12px] text-[#666666] mt-1">总资产价值</div>
          </div>
          <div class="p-3.5 sm:p-4 rounded-2xl bg-[#FAFAFA]">
            <div class="font-mono text-[19px] sm:text-[24px] font-semibold text-black">
              {{ wardrobeStore.preference.avgCostPerWear != null ? `¥${Number(wardrobeStore.preference.avgCostPerWear).toFixed(1)}` : '--' }}
            </div>
            <div class="text-[12px] text-[#666666] mt-1">平均 CPW</div>
          </div>
          <div class="p-3.5 sm:p-4 rounded-2xl bg-[#FAFAFA]">
            <div class="font-mono text-[19px] sm:text-[24px] font-semibold text-black">{{ wardrobeStore.preference.idleRate }}%</div>
            <div class="text-[12px] text-[#666666] mt-1">低频闲置率</div>
          </div>
        </div>

        <div class="grid grid-cols-1 sm:grid-cols-2 gap-3 text-[12.5px]">
          <div class="px-4 py-3 rounded-2xl bg-[#FAFAFA] flex items-center justify-between gap-3">
            <span class="text-[#666666] shrink-0">最值回票价</span>
            <span class="font-medium text-black truncate">
              {{ wardrobeStore.preference.bestValueItemName || '暂无' }}
              <strong v-if="wardrobeStore.preference.bestValueCpw != null" class="font-mono ml-1">
                ¥{{ Number(wardrobeStore.preference.bestValueCpw).toFixed(1) }}
              </strong>
            </span>
          </div>
          <div class="px-4 py-3 rounded-2xl bg-[#FAFAFA] flex items-center justify-between gap-3">
            <span class="text-[#666666] shrink-0">最高 CPW 待唤醒</span>
            <span class="font-medium text-black truncate">
              {{ wardrobeStore.preference.highestCpwItemName || '暂无' }}
              <strong v-if="wardrobeStore.preference.highestCpw != null" class="font-mono ml-1">
                ¥{{ Number(wardrobeStore.preference.highestCpw).toFixed(1) }}
              </strong>
            </span>
          </div>
        </div>
      </div>

      <div class="grid grid-cols-1 lg:grid-cols-2 gap-5">
        <!-- Category distribution -->
        <div class="rounded-3xl bg-white border border-black/[0.08] p-6">
          <h3 class="text-[16px] font-semibold tracking-tight text-black">品类分布</h3>
          <p class="text-[12.5px] text-[#666666] mt-0.5 mb-5">共 {{ wardrobeStore.items.length }} 件单品</p>

          <div class="flex flex-col sm:flex-row items-center gap-6">
            <div class="relative w-40 h-40 shrink-0">
              <svg viewBox="0 0 120 120" class="w-full h-full -rotate-90">
                <circle cx="60" cy="60" r="46" fill="none" stroke="#EBEBEB" stroke-width="14" />
                <circle
                  v-for="seg in donutSegments"
                  :key="seg.key"
                  cx="60"
                  cy="60"
                  r="46"
                  fill="none"
                  :stroke="seg.barColor"
                  stroke-width="14"
                  :stroke-dasharray="seg.dashArray"
                  :stroke-dashoffset="seg.dashOffset"
                  class="transition-all duration-500"
                />
              </svg>
              <div class="absolute inset-0 flex flex-col items-center justify-center">
                <span class="font-mono text-2xl font-semibold text-black">{{ wardrobeStore.items.length }}</span>
                <span class="text-[11px] text-[#666666]">总单品数</span>
              </div>
            </div>

            <div class="flex-1 w-full space-y-2.5">
              <div v-for="cat in categoryStats" :key="cat.key" class="flex items-center justify-between text-[13px]">
                <span class="flex items-center gap-2">
                  <span class="w-3 h-3 rounded-full" :style="{ backgroundColor: cat.barColor }" />
                  <span class="text-black font-medium">{{ cat.label }}</span>
                </span>
                <span class="font-mono text-[12.5px] text-[#666666]">
                  <strong class="text-black">{{ cat.count }}</strong> 件 ({{ cat.percent }}%)
                </span>
              </div>
              <div v-if="categoryStats.length === 0" class="text-[12.5px] text-[#666666]">暂无单品数据</div>
            </div>
          </div>
        </div>

        <!-- Style affinity + palette -->
        <div class="rounded-3xl bg-white border border-black/[0.08] p-6 space-y-5">
          <div>
            <h3 class="text-[16px] font-semibold tracking-tight text-black mb-4">隐性风格画像</h3>
            <div v-if="wardrobeStore.preference.styleAffinity.length > 0" class="space-y-3">
              <div v-for="st in wardrobeStore.preference.styleAffinity" :key="st.name">
                <div class="flex items-center justify-between text-[12.5px] mb-1">
                  <span class="text-black font-medium">{{ st.name }}</span>
                  <span class="font-mono text-[#666666]">{{ st.score }}%</span>
                </div>
                <div class="w-full h-2 rounded-full bg-[#EBEBEB] overflow-hidden">
                  <div class="h-full rounded-full bg-black transition-all duration-500" :style="{ width: st.score + '%' }" />
                </div>
              </div>
            </div>
            <p v-else class="text-[12.5px] text-[#666666]">录入衣物并积累穿搭历史后，将自动演算你的隐性风格画像。</p>
          </div>

          <div class="pt-5 border-t border-black/[0.08]">
            <h4 class="text-[13px] font-semibold text-black mb-2.5">衣橱主色盘</h4>
            <div class="flex h-8 rounded-2xl overflow-hidden border border-black/[0.08] bg-[#F5F5F5]">
              <div
                v-for="col in wardrobeStore.preference.colorStats"
                :key="col.color"
                :style="{ width: col.percent + '%', backgroundColor: col.hex }"
                :title="`${col.color} (${col.count}件)`"
                class="h-full transition-all duration-500"
              />
            </div>
            <div class="flex flex-wrap gap-x-3 gap-y-1.5 mt-2.5">
              <span
                v-for="col in wardrobeStore.preference.colorStats"
                :key="col.color"
                class="inline-flex items-center gap-1.5 text-[11.5px] text-[#666666]"
              >
                <span class="w-2.5 h-2.5 rounded-full border border-black/15" :style="{ backgroundColor: col.hex }" />
                {{ col.color }} <span class="font-mono">{{ col.count }}</span>
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- Style Mirror -->
      <div class="rounded-3xl bg-white border border-black/[0.08] p-6 space-y-5">
        <div>
          <h3 class="text-[16px] font-semibold tracking-tight text-black">风格魔镜</h3>
          <p class="text-[12.5px] text-[#666666] mt-0.5">自动学习你的高频配色组合、常穿王牌与待盘活冷宫单品</p>
        </div>

        <div v-if="(wardrobeStore.preference.colorPairFingerprints || []).length > 0">
          <div class="text-[12.5px] font-medium text-black mb-2">高频配色指纹</div>
          <div class="flex flex-wrap gap-2">
            <div
              v-for="pair in wardrobeStore.preference.colorPairFingerprints"
              :key="`${pair.colorA}-${pair.colorB}`"
              class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-[#FAFAFA] border border-black/[0.08] text-[12px]"
            >
              <span class="w-3 h-3 rounded-full border border-black/15" :style="{ backgroundColor: pair.hexA }" />
              <span class="text-black">{{ pair.colorA }}</span>
              <span class="text-[#666666]">+</span>
              <span class="w-3 h-3 rounded-full border border-black/15" :style="{ backgroundColor: pair.hexB }" />
              <span class="text-black">{{ pair.colorB }}</span>
              <span class="font-mono text-[11px] text-[#666666] ml-1">×{{ pair.count }}</span>
            </div>
          </div>
        </div>

        <div v-if="(wardrobeStore.preference.neglectedPieces || []).length > 0" class="space-y-2.5">
          <div class="flex items-center justify-between">
            <span class="text-[12.5px] font-medium text-black">被遗忘的冷宫单品</span>
            <span class="text-[11.5px] text-[#666666]">穿戴最少且值得盘活</span>
          </div>
          <div class="grid grid-cols-1 md:grid-cols-2 gap-2.5">
            <div
              v-for="item in wardrobeStore.preference.neglectedPieces"
              :key="item.id"
              class="flex items-center justify-between gap-3 p-2.5 rounded-2xl bg-[#FAFAFA]"
            >
              <div class="flex items-center gap-2.5 min-w-0">
                <div class="w-11 h-11 rounded-xl bg-white border border-black/[0.08] p-1 flex items-center justify-center shrink-0">
                  <GarmentVisual :image-url="item.imageUrl" :svg-type="item.svgType" :color-hex="item.colorHex" :alt="item.name" />
                </div>
                <div class="min-w-0">
                  <div class="text-[13px] font-medium text-black truncate">{{ item.name }}</div>
                  <div class="text-[11.5px] font-mono text-[#666666]">
                    已穿 {{ item.wearCount }} 次
                    <template v-if="item.costPerWear != null"> · CPW ¥{{ Number(item.costPerWear).toFixed(0) }}</template>
                  </div>
                </div>
              </div>
              <button
                type="button"
                @click="reviveNeglectedPiece(item.id, item.name)"
                class="px-3.5 py-1.5 rounded-full bg-black hover:bg-[#262626] text-white text-[11.5px] font-medium transition shrink-0"
              >
                一键盘活
              </button>
            </div>
          </div>
        </div>

        <div v-if="sortedStyleWeights.length > 0" class="pt-5 border-t border-black/[0.08] space-y-2.5">
          <div class="flex items-center justify-between">
            <span class="text-[12.5px] font-medium text-black">风格权重演化</span>
            <span class="text-[11.5px] text-[#666666]">收藏 / 踩雷 / 穿戴自动更新</span>
          </div>
          <div class="grid grid-cols-2 md:grid-cols-3 gap-2.5">
            <div v-for="sw in sortedStyleWeights" :key="sw.name" class="px-3 py-2.5 rounded-xl bg-[#FAFAFA]">
              <div class="flex items-center justify-between text-[12px] mb-1.5">
                <span class="text-black font-medium truncate">{{ sw.name }}</span>
                <span class="font-mono text-black">{{ sw.weight.toFixed(2) }}</span>
              </div>
              <div class="w-full h-1.5 rounded-full bg-[#EBEBEB] overflow-hidden">
                <div class="h-full rounded-full bg-black transition-all duration-500" :style="{ width: sw.percent + '%' }" />
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ================= Tab 2: 穿搭与收藏 ================= -->
    <div v-else-if="activeTab === 'calendar'" class="grid grid-cols-1 md:grid-cols-2 gap-5">
      <!-- Calendar entry -->
      <div class="rounded-3xl bg-white border border-black/[0.08] p-6 flex flex-col justify-between gap-6">
        <div class="space-y-5">
          <h3 class="text-[16px] font-semibold tracking-tight text-black">穿搭日历</h3>

          <div class="flex items-end gap-8">
            <div>
              <div class="font-mono text-[40px] font-semibold leading-none text-black">{{ monthRecordCount }}</div>
              <div class="text-[12px] text-[#666666] mt-2">本月已记录（天）</div>
            </div>
            <div>
              <div class="font-mono text-[40px] font-semibold leading-none text-black/30">{{ lookbookStore.history.length }}</div>
              <div class="text-[12px] text-[#666666] mt-2">累计档案（天）</div>
            </div>
          </div>

          <button
            v-if="latestRecord"
            @click="openLatestRecord"
            class="w-full p-3.5 rounded-2xl bg-[#FAFAFA] hover:bg-[#F2F2F2] transition flex items-center justify-between gap-3 text-left"
          >
            <div class="min-w-0">
              <div class="font-mono text-[11.5px] text-[#666666]">最近一次 · {{ latestRecord.wearDate }}</div>
              <div class="text-[13.5px] font-semibold text-black truncate mt-0.5">{{ latestRecord.themeTitle }}</div>
            </div>
            <div class="flex items-center gap-2 shrink-0">
              <div class="flex -space-x-1">
                <span
                  v-for="(c, idx) in latestRecord.palette.slice(0, 3)"
                  :key="idx"
                  class="w-3.5 h-3.5 rounded-full border border-white"
                  :style="{ backgroundColor: c }"
                />
              </div>
              <AppIcon name="chevron-right" :size="16" class="text-[#666666]" />
            </div>
          </button>
          <p v-else class="text-[12.5px] text-[#666666] py-3">还没有穿搭记录，在平铺画板里记录今天的穿搭吧。</p>
        </div>

        <button
          @click="router.push('/calendar')"
          class="w-full py-3 rounded-full bg-black hover:bg-[#262626] text-white text-[13.5px] font-medium transition flex items-center justify-center gap-2"
        >
          <AppIcon name="calendar" :size="15" />
          打开穿搭日历
        </button>
      </div>

      <!-- Favorites entry -->
      <div class="rounded-3xl bg-white border border-black/[0.08] p-6 flex flex-col justify-between gap-6">
        <div class="space-y-5">
          <h3 class="text-[16px] font-semibold tracking-tight text-black">收藏方案</h3>

          <div>
            <div class="font-mono text-[40px] font-semibold leading-none text-black">{{ lookbookStore.favorites.length }}</div>
            <div class="text-[12px] text-[#666666] mt-2">已收藏搭配（套）</div>
          </div>

          <div v-if="recentFavorites.length > 0" class="space-y-2.5">
            <div
              v-for="look in recentFavorites"
              :key="look.id"
              @click="openFavoriteLook(look)"
              class="p-3 rounded-2xl bg-[#FAFAFA] hover:bg-[#F2F2F2] cursor-pointer transition flex items-center justify-between gap-3"
            >
              <div class="flex items-center gap-2 min-w-0">
                <div class="flex -space-x-2 shrink-0">
                  <div
                    v-for="(s, i) in look.slots.slice(0, 3)"
                    :key="i"
                    class="w-10 h-10 rounded-xl bg-white border border-black/[0.08] p-1 flex items-center justify-center"
                  >
                    <GarmentVisual :image-url="s.item.processedImageUrl" :svg-type="s.item.svgType" :color-hex="s.item.colorHex" />
                  </div>
                </div>
                <div class="min-w-0 pl-1">
                  <div class="text-[13.5px] font-semibold text-black truncate">{{ look.themeTitle }}</div>
                  <div class="text-[11.5px] text-[#666666] truncate">{{ look.sceneTag }} · 匹配度 {{ look.matchScore }}%</div>
                </div>
              </div>
              <AppIcon name="chevron-right" :size="16" class="text-[#666666] shrink-0" />
            </div>
          </div>
          <p v-else class="text-[12.5px] text-[#666666] py-3">暂未收藏方案，去搭配画板生成一套吧。</p>
        </div>

        <button
          @click="router.push({ path: '/calendar', query: { tab: 'favorites' } })"
          class="w-full py-3 rounded-full bg-[#F5F5F5] hover:bg-black hover:text-white text-black text-[13.5px] font-medium transition flex items-center justify-center gap-2"
        >
          查看全部收藏
          <AppIcon name="arrow-right" :size="15" />
        </button>
      </div>
    </div>

    <!-- ================= Tab 3: 偏好与设置 ================= -->
    <div v-else class="space-y-5">
      <!-- Account -->
      <div class="rounded-3xl bg-white border border-black/[0.08] p-5 flex items-center justify-between gap-4">
        <div class="flex items-center gap-3.5 min-w-0">
          <span class="w-11 h-11 rounded-full bg-black text-white flex items-center justify-center text-[15px] font-semibold shrink-0">
            {{ (authStore.user?.nickname || '我').slice(0, 1) }}
          </span>
          <div class="min-w-0">
            <div class="text-[15px] font-semibold text-black truncate">{{ authStore.user?.nickname }}</div>
            <div class="text-[12px] font-mono text-[#666666] truncate">@{{ authStore.user?.username }}</div>
          </div>
        </div>
        <button
          @click="handleLogout"
          class="inline-flex items-center gap-1.5 px-4 py-2 rounded-full bg-[#F5F5F5] hover:bg-black hover:text-white text-[13px] font-medium text-black transition shrink-0"
        >
          <AppIcon name="log-out" :size="14" />
          退出登录
        </button>
      </div>

      <div class="grid grid-cols-1 lg:grid-cols-2 gap-5 items-start">
        <!-- Style & color preferences -->
        <div class="rounded-3xl bg-white border border-black/[0.08] p-6 space-y-6">
          <h3 class="text-[16px] font-semibold tracking-tight text-black">穿衣偏好</h3>

          <div>
            <label class="block text-[12.5px] font-medium text-black mb-2.5">常穿风格（点击切换）</label>
            <div class="flex flex-wrap gap-2">
              <button
                v-for="st in ALL_STYLE_OPTIONS"
                :key="st"
                @click="toggleStyle(st)"
                class="px-3.5 py-1.5 rounded-full text-[13px] font-medium border transition"
                :class="
                  wardrobeStore.preference.favoriteStyles.includes(st)
                    ? 'bg-black text-white border-black'
                    : 'bg-[#FAFAFA] text-[#333333] border-black/[0.08] hover:bg-[#F5F5F5]'
                "
              >
                {{ wardrobeStore.preference.favoriteStyles.includes(st) ? '✓ ' : '+ ' }}{{ st }}
              </button>
            </div>
          </div>

          <div>
            <label class="block text-[12.5px] font-medium text-black mb-2.5">偏好色系</label>
            <div class="flex flex-wrap gap-2">
              <button
                v-for="sw in COLOR_SWATCHES"
                :key="'fav-' + sw.name"
                @click="toggleColor('favoriteColors', sw.name)"
                class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full border text-[12.5px] transition"
                :class="
                  wardrobeStore.preference.favoriteColors.includes(sw.name)
                    ? 'bg-black text-white border-black font-medium'
                    : 'bg-white border-black/[0.08] text-[#666666] hover:border-black/30'
                "
              >
                <span class="w-3 h-3 rounded-full border border-black/15" :style="{ backgroundColor: sw.hex }" />
                {{ sw.name }}
              </button>
            </div>
          </div>

          <div>
            <label class="block text-[12.5px] font-medium text-black mb-2.5">避雷色系（推荐时自动降权）</label>
            <div class="flex flex-wrap gap-2">
              <button
                v-for="c in DISLIKED_COLOR_OPTIONS"
                :key="c"
                @click="toggleColor('dislikedColors', c)"
                class="px-3.5 py-1.5 rounded-full border text-[12.5px] transition"
                :class="
                  wardrobeStore.preference.dislikedColors.includes(c)
                    ? 'bg-black text-white border-black font-medium'
                    : 'bg-[#FAFAFA] border-black/[0.08] text-[#666666] hover:border-black/30'
                "
              >
                {{ wardrobeStore.preference.dislikedColors.includes(c) ? '✕ ' : '' }}{{ c }}
              </button>
            </div>
          </div>
        </div>

        <div class="space-y-5">
          <!-- City, weather & budget -->
          <div class="rounded-3xl bg-white border border-black/[0.08] p-6 space-y-5">
            <h3 class="text-[16px] font-semibold tracking-tight text-black">城市与预算</h3>

            <div>
              <label class="block text-[12.5px] font-medium text-black mb-1.5">常驻城市（支持输入全国任意城市）</label>
              <div class="flex gap-2">
                <input
                  v-model="cityInput"
                  list="wardrobe-city-options"
                  @keyup.enter="updateCity()"
                  type="text"
                  placeholder="如 武汉 / 哈尔滨 / 厦门"
                  class="flex-1 min-w-0 px-3.5 py-2 rounded-xl bg-[#F5F5F5] border border-transparent focus:border-black focus:bg-white text-[13.5px] text-black outline-none transition"
                />
                <datalist id="wardrobe-city-options">
                  <option v-for="city in CITY_OPTIONS" :key="city" :value="city" />
                </datalist>
                <button
                  type="button"
                  @click="updateCity()"
                  :disabled="refreshingWeather"
                  class="px-4 py-2 rounded-xl bg-black hover:bg-[#262626] text-white text-[12.5px] font-medium transition shrink-0 disabled:opacity-60"
                >
                  {{ refreshingWeather ? '查询中' : '同步' }}
                </button>
              </div>
              <div class="mt-2.5 px-3.5 py-2.5 rounded-xl bg-[#FAFAFA] flex items-center justify-between gap-2 text-[12px]">
                <div class="flex items-center gap-1.5 min-w-0 text-[#333333] leading-snug">
                  <span
                    class="w-1.5 h-1.5 rounded-full shrink-0"
                    :class="wardrobeStore.liveWeather.live ? 'bg-emerald-500' : 'bg-[#999999]'"
                  />
                  <span>
                    {{ wardrobeStore.liveWeather.city }} · {{ wardrobeStore.liveWeather.condition }}
                    <strong class="font-mono text-black">{{ wardrobeStore.liveWeather.temp }}°C</strong>
                    <template v-if="wardrobeStore.liveWeather.humidity != null">
                      · 体感 <span class="font-mono">{{ wardrobeStore.liveWeather.apparentTemp ?? wardrobeStore.liveWeather.temp }}°C</span>
                      · 湿度 <span class="font-mono">{{ wardrobeStore.liveWeather.humidity }}%</span>
                    </template>
                  </span>
                </div>
                <button
                  type="button"
                  @click="refreshLiveWeather"
                  :disabled="refreshingWeather"
                  class="text-black hover:underline shrink-0 font-medium"
                  :title="`${wardrobeStore.liveWeather.source || 'Open-Meteo'} 更新于 ${wardrobeStore.liveWeather.updatedAt || '--'}`"
                >
                  刷新
                </button>
              </div>
            </div>

            <div>
              <div class="flex items-center justify-between mb-1.5">
                <label class="text-[12.5px] font-medium text-black">默认单品预算上限</label>
                <span class="font-mono text-[13.5px] font-semibold text-black">
                  ¥{{ Math.round(Number(wardrobeStore.preference.budgetLimit) || 300) }}
                </span>
              </div>
              <input
                :value="Number(wardrobeStore.preference.budgetLimit) || 300"
                @change="updateBudget(Number(($event.target as HTMLInputElement).value))"
                type="range"
                min="100"
                max="800"
                step="20"
                class="w-full accent-black mt-2"
              />
            </div>
          </div>

          <!-- Body shape & seasonal color -->
          <div class="rounded-3xl bg-white border border-black/[0.08] p-6 space-y-5">
            <div class="flex flex-wrap items-start justify-between gap-2">
              <div>
                <h3 class="text-[16px] font-semibold tracking-tight text-black">身形与四季色彩</h3>
                <p class="text-[12px] text-[#666666] mt-0.5">参与推荐打分、灵感排序与 AI 上身比例控制</p>
              </div>
              <div class="inline-flex items-center gap-1.5 text-[12.5px]">
                <span class="text-[#666666]">身高</span>
                <input
                  v-model.number="heightInput"
                  @change="saveHeight"
                  type="number"
                  min="135"
                  max="210"
                  class="w-16 px-2 py-1 rounded-lg bg-[#F5F5F5] border border-transparent focus:border-black focus:bg-white font-mono text-center text-[12.5px] text-black outline-none"
                />
                <span class="font-mono text-[#666666]">cm</span>
              </div>
            </div>

            <div>
              <div class="text-[12.5px] font-medium text-black mb-2">个人身形</div>
              <div class="flex flex-wrap gap-1.5">
                <button
                  v-for="shape in BODY_SHAPES"
                  :key="shape"
                  type="button"
                  @click="selectBodyShape(shape)"
                  class="px-3.5 py-1.5 rounded-full text-[12.5px] font-medium border transition"
                  :class="
                    (wardrobeStore.preference.bodyShape || 'H型直筒') === shape
                      ? 'bg-black text-white border-black'
                      : 'bg-white text-[#333333] border-black/[0.08] hover:border-black'
                  "
                >
                  {{ shape }}
                </button>
              </div>
              <p v-if="wardrobeStore.preference.bodyShapeAdvice" class="mt-2 text-[12px] text-[#666666] leading-relaxed">
                {{ wardrobeStore.preference.bodyShapeAdvice }}
              </p>
            </div>

            <div>
              <div class="text-[12.5px] font-medium text-black mb-2">四季色彩季型</div>
              <div class="flex flex-wrap gap-1.5">
                <button
                  v-for="season in COLOR_SEASONS"
                  :key="season"
                  type="button"
                  @click="selectColorSeason(season)"
                  class="px-3.5 py-1.5 rounded-full text-[12.5px] font-medium border transition"
                  :class="
                    (wardrobeStore.preference.colorSeason || '中性百搭') === season
                      ? 'bg-black text-white border-black'
                      : 'bg-white text-[#333333] border-black/[0.08] hover:border-black'
                  "
                >
                  {{ season }}
                </button>
              </div>
              <p v-if="wardrobeStore.preference.colorSeasonAdvice" class="mt-2 text-[12px] text-[#666666] leading-relaxed">
                {{ wardrobeStore.preference.colorSeasonAdvice }}
              </p>
            </div>
          </div>
        </div>
      </div>

      <!-- Advanced: AI model config (collapsed by default) -->
      <div ref="aiPanelRef" class="scroll-mt-20">
        <button
          type="button"
          @click="aiPanelOpen = !aiPanelOpen"
          :aria-expanded="aiPanelOpen"
          aria-controls="ai-config-panel"
          class="w-full flex items-center justify-between gap-3 rounded-3xl bg-white border border-black/[0.08] hover:bg-[#FAFAFA] px-6 py-4 text-left transition"
        >
          <div class="min-w-0">
            <div class="text-[15px] font-semibold text-black">高级设置：AI 模型配置</div>
            <div class="text-[12px] text-[#666666] mt-0.5 flex items-center gap-1.5">
              <span class="w-1.5 h-1.5 rounded-full shrink-0" :class="aiConfigActive ? 'bg-emerald-500' : 'bg-[#999999]'" />
              {{ aiConfigActive ? '服务端模型已激活' : '本地降级模式 · 可配置 OpenAI 兼容 API Key' }}
            </div>
          </div>
          <AppIcon
            name="chevron-right"
            :size="18"
            class="text-[#666666] shrink-0 transition-transform duration-200"
            :class="aiPanelOpen ? 'rotate-90' : ''"
          />
        </button>
        <div v-if="aiPanelOpen" id="ai-config-panel" class="mt-3">
          <AiModelConfigCard />
        </div>
      </div>
    </div>
  </section>
</template>
