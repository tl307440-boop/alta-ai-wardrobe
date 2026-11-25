<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
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
import AppIcon from '@/components/AppIcon.vue';
import AiModelConfigCard from '@/components/AiModelConfigCard.vue';
import GarmentVisual from '@/components/GarmentVisual.vue';

const router = useRouter();
const authStore = useAuthStore();
const wardrobeStore = useWardrobeStore();
const lookbookStore = useLookbookStore();
const toast = useToastStore();

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

const sortedStyleWeights = computed(() =>
  Object.entries(wardrobeStore.preference.styleWeights || {})
    .map(([name, weight]) => ({ name, weight: Number(weight) || 0, percent: Math.round((Number(weight) || 0) * 100) }))
    .sort((a, b) => b.weight - a.weight)
    .slice(0, 6)
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

async function updateBudget(val: number) {
  await wardrobeStore.updatePreference({ budgetLimit: val });
  lookbookStore.budget = val;
  toast.show(`默认预算上限已同步为 ¥${val}`);
}

function handleSwitchAccount() {
  authStore.logout();
  wardrobeStore.reset();
  lookbookStore.reset();
  router.replace('/login');
}
</script>

<template>
  <section class="grid grid-cols-1 lg:grid-cols-12 gap-6">
    <!-- Left Column: Personal Preferences + Server AI Model API Key Config -->
    <div class="lg:col-span-6 space-y-5">
      <div class="bg-white rounded-3xl border border-oat-300 shadow-editorial p-6 space-y-6">
        <div class="flex items-start justify-between gap-3">
          <div>
            <h3 class="font-serif text-xl font-semibold text-charcoal">个人穿衣偏好与账号信息</h3>
            <p class="text-[12.5px] text-warmgray mt-1">
              当前账号：{{ authStore.user?.nickname }} (<span class="font-mono">@{{ authStore.user?.username }}</span>)
            </p>
          </div>
          <button
            @click="handleSwitchAccount"
            class="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-full bg-oat-100 hover:bg-terracotta-light hover:text-terracotta-dark text-[12px] font-medium text-charcoal transition"
          >
            <AppIcon name="log-out" :size="14" />
            切换账号
          </button>
        </div>

        <div>
          <label class="block text-[12.5px] font-medium text-charcoal mb-2.5">常穿风格偏好（点击切换）</label>
          <div class="flex flex-wrap gap-2">
            <button
              v-for="st in ALL_STYLE_OPTIONS"
              :key="st"
              @click="toggleStyle(st)"
              class="px-3.5 py-1.5 rounded-full text-[13px] font-medium border transition"
              :class="
                wardrobeStore.preference.favoriteStyles.includes(st)
                  ? 'bg-charcoal text-white border-charcoal'
                  : 'bg-oat-50 text-charcoal-soft border-oat-300 hover:bg-oat-100'
              "
            >
              {{ wardrobeStore.preference.favoriteStyles.includes(st) ? '✓ ' : '+ ' }}{{ st }}
            </button>
          </div>
        </div>

        <div>
          <label class="block text-[12.5px] font-medium text-charcoal mb-2.5">偏好色系</label>
          <div class="flex flex-wrap gap-2">
            <button
              v-for="sw in COLOR_SWATCHES"
              :key="'fav-' + sw.name"
              @click="toggleColor('favoriteColors', sw.name)"
              class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full border text-[12px] transition"
              :class="
                wardrobeStore.preference.favoriteColors.includes(sw.name)
                  ? 'bg-terracotta-light border-terracotta text-terracotta-dark font-medium'
                  : 'bg-white border-oat-300 text-warmgray'
              "
            >
              <span class="w-3 h-3 rounded-full border border-black/15" :style="{ backgroundColor: sw.hex }" />
              {{ sw.name }}
            </button>
          </div>
        </div>

        <div>
          <label class="block text-[12.5px] font-medium text-charcoal mb-2.5">避雷色系（推荐时自动降权）</label>
          <div class="flex flex-wrap gap-2">
            <button
              v-for="c in DISLIKED_COLOR_OPTIONS"
              :key="c"
              @click="toggleColor('dislikedColors', c)"
              class="px-3 py-1 rounded-full border text-[12px] transition"
              :class="
                wardrobeStore.preference.dislikedColors.includes(c)
                  ? 'bg-red-50 border-red-300 text-red-700 font-medium'
                  : 'bg-oat-50 border-oat-300 text-warmgray'
              "
            >
              {{ c }}
            </button>
          </div>
        </div>

        <div class="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-3 border-t border-oat-200">
          <div>
            <label class="block text-[12.5px] font-medium text-charcoal mb-1.5">常驻城市（支持输入全国任意城市）</label>
            <div class="flex gap-2">
              <input
                v-model="cityInput"
                list="wardrobe-city-options"
                @keyup.enter="updateCity()"
                type="text"
                placeholder="输入城市名，如 武汉 / 哈尔滨 / 厦门"
                class="flex-1 min-w-0 px-3.5 py-2 rounded-xl bg-oat-50 border border-oat-300 focus:border-charcoal focus:bg-white text-[13.5px] text-charcoal outline-none transition"
              />
              <datalist id="wardrobe-city-options">
                <option v-for="city in CITY_OPTIONS" :key="city" :value="city" />
              </datalist>
              <button
                type="button"
                @click="updateCity()"
                :disabled="refreshingWeather"
                class="px-3.5 py-2 rounded-xl bg-charcoal hover:bg-charcoal-hover text-white text-[12px] font-medium transition shrink-0 disabled:opacity-60"
              >
                {{ refreshingWeather ? '查询中' : '同步' }}
              </button>
            </div>
            <div class="mt-2 px-3 py-2 rounded-xl bg-oat-50 border border-oat-200 flex items-center justify-between gap-2 text-[11px]">
              <div class="flex items-center gap-1.5 min-w-0 text-charcoal-soft leading-snug">
                <span
                  class="w-1.5 h-1.5 rounded-full shrink-0"
                  :class="wardrobeStore.liveWeather.live ? 'bg-emerald-500' : 'bg-terracotta'"
                />
                <span>
                  {{ wardrobeStore.liveWeather.city }} · {{ wardrobeStore.liveWeather.condition }}
                  <strong class="font-mono text-charcoal">{{ wardrobeStore.liveWeather.temp }}°C</strong>
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
                class="text-terracotta hover:underline shrink-0 font-medium"
                :title="`${wardrobeStore.liveWeather.source || 'Open-Meteo'} 更新于 ${wardrobeStore.liveWeather.updatedAt || '--'}`"
              >
                刷新
              </button>
            </div>
          </div>
          <div>
            <div class="flex items-center justify-between mb-1.5">
              <label class="text-[12.5px] font-medium text-charcoal">默认单品预算上限</label>
              <span class="font-mono text-[13.5px] font-semibold text-terracotta">
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

        <!-- Body Shape + Seasonal Color Analysis Profile -->
        <div class="pt-4 border-t border-oat-200 space-y-4">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <div>
              <h4 class="text-[13px] font-semibold text-charcoal">身形廓形 &amp; 四季色彩季型档案</h4>
              <p class="text-[11.5px] text-warmgray">直接参与推荐引擎打分、灵感排序、大模型造型与 AI 上身比例控制</p>
            </div>
            <div class="inline-flex items-center gap-1.5 text-[12px]">
              <span class="text-warmgray">身高</span>
              <input
                v-model.number="heightInput"
                @change="saveHeight"
                type="number"
                min="135"
                max="210"
                class="w-16 px-2 py-1 rounded-lg bg-oat-50 border border-oat-300 font-mono text-center text-[12px] text-charcoal outline-none focus:border-charcoal"
              />
              <span class="font-mono text-warmgray">cm</span>
            </div>
          </div>

          <div>
            <div class="text-[12px] font-medium text-charcoal mb-2">个人身形</div>
            <div class="flex flex-wrap gap-1.5">
              <button
                v-for="shape in BODY_SHAPES"
                :key="shape"
                type="button"
                @click="selectBodyShape(shape)"
                class="px-3 py-1 rounded-full text-[12px] font-medium border transition"
                :class="
                  (wardrobeStore.preference.bodyShape || 'H型直筒') === shape
                    ? 'bg-charcoal text-white border-charcoal'
                    : 'bg-white text-charcoal-soft border-oat-300 hover:border-charcoal'
                "
              >
                {{ shape }}
              </button>
            </div>
            <p v-if="wardrobeStore.preference.bodyShapeAdvice" class="mt-2 text-[11.5px] text-warmgray leading-relaxed">
              {{ wardrobeStore.preference.bodyShapeAdvice }}
            </p>
          </div>

          <div>
            <div class="text-[12px] font-medium text-charcoal mb-2">四季色彩季型（Seasonal Palette）</div>
            <div class="flex flex-wrap gap-1.5">
              <button
                v-for="season in COLOR_SEASONS"
                :key="season"
                type="button"
                @click="selectColorSeason(season)"
                class="px-3 py-1 rounded-full text-[12px] font-medium border transition"
                :class="
                  (wardrobeStore.preference.colorSeason || '中性百搭') === season
                    ? 'bg-charcoal text-white border-charcoal'
                    : 'bg-white text-charcoal-soft border-oat-300 hover:border-charcoal'
                "
              >
                {{ season }}
              </button>
            </div>
            <p v-if="wardrobeStore.preference.colorSeasonAdvice" class="mt-2 text-[11.5px] text-warmgray leading-relaxed">
              {{ wardrobeStore.preference.colorSeasonAdvice }}
            </p>
          </div>
        </div>
      </div>

      <!-- Server-Side Model API Key Configuration Card (matches Warm Oat Editorial UI) -->
      <AiModelConfigCard />
    </div>

    <!-- Right Column: Live Analytics & CPW Asset Summary -->
    <div class="lg:col-span-6 space-y-5">
      <!-- Quick Links to Calendar & Favorites + Indyx CPW Card -->
      <div class="bg-white rounded-3xl border border-oat-300 shadow-editorial p-6 space-y-4">
        <div class="flex items-center justify-between">
          <div>
            <h3 class="text-lg font-semibold text-charcoal">衣橱资产与 CPW 深度报告</h3>
            <p class="text-[12px] text-warmgray">借鉴 Indyx 单次穿着成本模型，实时衡量每件单品的真实利用率</p>
          </div>
          <button
            @click="router.push('/calendar')"
            class="px-3.5 py-1.5 rounded-full bg-oat-100 hover:bg-charcoal hover:text-white text-[12px] font-medium text-charcoal transition shrink-0"
          >
            穿搭日历 ({{ lookbookStore.history.length }}) →
          </button>
        </div>

        <div class="grid grid-cols-3 gap-2.5">
          <div class="p-3.5 rounded-2xl bg-oat-50 border border-oat-200">
            <div class="font-mono text-[19px] font-semibold text-charcoal">
              ¥{{ Number(wardrobeStore.preference.totalWardrobeValue || 0).toLocaleString('zh-CN', { maximumFractionDigits: 0 }) }}
            </div>
            <div class="text-[11px] text-warmgray mt-0.5">总资产价值</div>
          </div>
          <div class="p-3.5 rounded-2xl bg-oat-50 border border-oat-200">
            <div class="font-mono text-[19px] font-semibold text-charcoal">
              {{ wardrobeStore.preference.avgCostPerWear != null ? `¥${Number(wardrobeStore.preference.avgCostPerWear).toFixed(1)}` : '--' }}
            </div>
            <div class="text-[11px] text-warmgray mt-0.5">平均 CPW / 次</div>
          </div>
          <div class="p-3.5 rounded-2xl bg-oat-50 border border-oat-200">
            <div class="font-mono text-[19px] font-semibold text-charcoal">{{ wardrobeStore.preference.idleRate }}%</div>
            <div class="text-[11px] text-warmgray mt-0.5">低频闲置率</div>
          </div>
        </div>

        <div class="grid grid-cols-1 sm:grid-cols-2 gap-2.5 text-[12px]">
          <div class="p-3 rounded-2xl bg-oat-50 flex items-center justify-between gap-2">
            <span class="text-warmgray">最值回票价</span>
            <span class="font-medium text-charcoal truncate">
              {{ wardrobeStore.preference.bestValueItemName || '暂无' }}
              <strong v-if="wardrobeStore.preference.bestValueCpw != null" class="font-mono text-emerald-700 ml-1">
                ¥{{ Number(wardrobeStore.preference.bestValueCpw).toFixed(1) }}
              </strong>
            </span>
          </div>
          <div class="p-3 rounded-2xl bg-oat-50 flex items-center justify-between gap-2">
            <span class="text-warmgray">最高 CPW 待唤醒</span>
            <span class="font-medium text-charcoal truncate">
              {{ wardrobeStore.preference.highestCpwItemName || '暂无' }}
              <strong v-if="wardrobeStore.preference.highestCpw != null" class="font-mono text-charcoal ml-1">
                ¥{{ Number(wardrobeStore.preference.highestCpw).toFixed(1) }}
              </strong>
            </span>
          </div>
        </div>
      </div>

      <div class="bg-white rounded-3xl border border-oat-300 shadow-editorial p-6">
        <div class="flex items-center justify-between mb-4">
          <div>
            <h3 class="font-serif text-lg font-semibold text-charcoal">实时衣橱品类分布</h3>
            <p class="text-[12px] text-warmgray">
              根据 @{{ authStore.user?.username }} 当前 {{ wardrobeStore.items.length }} 件真实衣物动态计算
            </p>
          </div>
          <span class="font-mono text-[11px] text-terracotta bg-terracotta-light px-2.5 py-1 rounded-full">
            LIVE ANALYTICS
          </span>
        </div>

        <div class="flex flex-col sm:flex-row items-center gap-6">
          <div class="relative w-44 h-44 shrink-0">
            <svg viewBox="0 0 120 120" class="w-full h-full -rotate-90">
              <circle cx="60" cy="60" r="46" fill="none" stroke="#EFECE6" stroke-width="14" />
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
              <span class="font-mono text-2xl font-bold text-charcoal">{{ wardrobeStore.items.length }}</span>
              <span class="text-[11px] text-warmgray">总单品数</span>
            </div>
          </div>

          <div class="flex-1 w-full space-y-2.5">
            <div v-for="cat in categoryStats" :key="cat.key" class="flex items-center justify-between text-[13px]">
              <span class="flex items-center gap-2">
                <span class="w-3 h-3 rounded-full" :style="{ backgroundColor: cat.barColor }" />
                <span class="text-charcoal font-medium">{{ cat.label }}</span>
              </span>
              <span class="font-mono text-[12.5px] text-warmgray">
                <strong class="text-charcoal">{{ cat.count }}</strong> 件 ({{ cat.percent }}%)
              </span>
            </div>
            <div v-if="categoryStats.length === 0" class="text-[12.5px] text-warmgray">暂无单品数据</div>
          </div>
        </div>
      </div>

      <div class="bg-white rounded-3xl border border-oat-300 shadow-editorial p-6 space-y-5">
        <div>
          <h3 class="font-serif text-lg font-semibold text-charcoal mb-3">隐性风格倾向画像</h3>
          <div v-if="wardrobeStore.preference.styleAffinity.length > 0" class="space-y-3">
            <div v-for="st in wardrobeStore.preference.styleAffinity" :key="st.name">
              <div class="flex items-center justify-between text-[12.5px] mb-1">
                <span class="text-charcoal font-medium">{{ st.name }}</span>
                <span class="font-mono text-warmgray">{{ st.score }}%</span>
              </div>
              <div class="w-full h-2 rounded-full bg-oat-200 overflow-hidden">
                <div
                  class="h-full rounded-full bg-terracotta transition-all duration-500"
                  :style="{ width: st.score + '%' }"
                />
              </div>
            </div>
          </div>
          <p v-else class="text-[12.5px] text-warmgray">录入衣物并积累穿搭历史后，将自动演算你的隐性风格画像。</p>
        </div>

        <div class="pt-4 border-t border-oat-200">
          <h4 class="text-[13px] font-semibold text-charcoal mb-2.5">衣橱主色盘分布</h4>
          <div class="flex h-8 rounded-2xl overflow-hidden border border-oat-300 bg-oat-100">
            <div
              v-for="col in wardrobeStore.preference.colorStats"
              :key="col.color"
              :style="{ width: col.percent + '%', backgroundColor: col.hex }"
              :title="`${col.color} (${col.count}件)`"
              class="h-full transition-all duration-500"
            />
          </div>
          <div class="flex flex-wrap gap-2 mt-2.5">
            <span
              v-for="col in wardrobeStore.preference.colorStats"
              :key="col.color"
              class="inline-flex items-center gap-1 text-[11px] text-warmgray"
            >
              <span class="w-2.5 h-2.5 rounded-full border border-black/15" :style="{ backgroundColor: col.hex }" />
              {{ col.color }} <span class="font-mono">{{ col.count }}</span>
            </span>
          </div>
        </div>
      </div>

      <!-- Style Mirror ("The Mirror" inspired by tandpfun/wardrobe) -->
      <div class="bg-white rounded-3xl border border-oat-300 shadow-editorial p-6 space-y-5">
        <div class="flex items-center justify-between gap-2">
          <div>
            <h3 class="text-lg font-semibold text-charcoal">风格魔镜 · 个人穿搭指纹</h3>
            <p class="text-[12px] text-warmgray">自动学习你的高频配色组合、常穿王牌与待盘活冷宫单品</p>
          </div>
          <span class="font-mono text-[10.5px] px-2.5 py-1 rounded-full bg-oat-100 text-charcoal border border-oat-300">
            THE MIRROR
          </span>
        </div>

        <!-- Learned Color-Pairing Fingerprints -->
        <div v-if="(wardrobeStore.preference.colorPairFingerprints || []).length > 0">
          <div class="text-[12px] font-medium text-charcoal mb-2">你的高频配色指纹</div>
          <div class="flex flex-wrap gap-2">
            <div
              v-for="pair in wardrobeStore.preference.colorPairFingerprints"
              :key="`${pair.colorA}-${pair.colorB}`"
              class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-oat-50 border border-oat-200 text-[12px]"
            >
              <span class="w-3 h-3 rounded-full border border-black/15" :style="{ backgroundColor: pair.hexA }" />
              <span class="text-charcoal">{{ pair.colorA }}</span>
              <span class="text-warmgray">+</span>
              <span class="w-3 h-3 rounded-full border border-black/15" :style="{ backgroundColor: pair.hexB }" />
              <span class="text-charcoal">{{ pair.colorB }}</span>
              <span class="font-mono text-[10.5px] text-warmgray ml-1">×{{ pair.count }}</span>
            </div>
          </div>
        </div>

        <!-- Neglected Pieces -> One-Click Revive -->
        <div v-if="(wardrobeStore.preference.neglectedPieces || []).length > 0" class="space-y-2.5">
          <div class="flex items-center justify-between">
            <span class="text-[12px] font-medium text-charcoal">被遗忘的冷宫单品 · 一键唤醒</span>
            <span class="text-[11px] text-warmgray">穿戴最少且值得盘活</span>
          </div>
          <div class="space-y-2">
            <div
              v-for="item in wardrobeStore.preference.neglectedPieces"
              :key="item.id"
              class="flex items-center justify-between gap-3 p-2.5 rounded-2xl bg-oat-50 border border-oat-200"
            >
              <div class="flex items-center gap-2.5 min-w-0">
                <div class="w-10 h-10 rounded-xl bg-white border border-black/[0.08] p-1 flex items-center justify-center shrink-0">
                  <GarmentVisual
                    :image-url="item.imageUrl"
                    :svg-type="item.svgType"
                    :color-hex="item.colorHex"
                    :alt="item.name"
                  />
                </div>
                <div class="min-w-0">
                  <div class="text-[12.5px] font-medium text-charcoal truncate">{{ item.name }}</div>
                  <div class="text-[11px] font-mono text-warmgray">
                    已穿 {{ item.wearCount }} 次
                    <template v-if="item.costPerWear != null"> · CPW ¥{{ Number(item.costPerWear).toFixed(0) }}</template>
                  </div>
                </div>
              </div>
              <button
                type="button"
                @click="reviveNeglectedPiece(item.id, item.name)"
                class="px-3 py-1.5 rounded-full bg-charcoal hover:bg-charcoal-hover text-white text-[11px] font-medium transition shrink-0"
              >
                一键盘活 →
              </button>
            </div>
          </div>
        </div>

        <!-- Real-Time Style Weights Evolution -->
        <div v-if="sortedStyleWeights.length > 0" class="pt-3 border-t border-oat-200 space-y-2">
          <div class="flex items-center justify-between">
            <span class="text-[12px] font-medium text-charcoal">实时风格权重演化（收藏/踩雷/穿戴自动更新）</span>
            <span class="font-mono text-[10.5px] text-warmgray">0.10 ~ 1.00</span>
          </div>
          <div class="grid grid-cols-2 gap-2">
            <div
              v-for="sw in sortedStyleWeights"
              :key="sw.name"
              class="px-3 py-2 rounded-xl bg-oat-50 border border-oat-200"
            >
              <div class="flex items-center justify-between text-[11.5px] mb-1">
                <span class="text-charcoal font-medium truncate">{{ sw.name }}</span>
                <span class="font-mono text-charcoal">{{ sw.weight.toFixed(2) }}</span>
              </div>
              <div class="w-full h-1.5 rounded-full bg-oat-200 overflow-hidden">
                <div class="h-full rounded-full bg-black transition-all duration-500" :style="{ width: sw.percent + '%' }" />
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
