<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useLookbookStore } from '@/stores/lookbook';
import { useToastStore } from '@/stores/toast';
import { CATEGORY_OPTIONS, COLOR_SWATCHES, categoryLabel } from '@/utils/constants';
import type { WardrobeItem } from '@/types';
import AppIcon from '@/components/AppIcon.vue';
import GarmentVisual from '@/components/GarmentVisual.vue';

type SortKey = 'recent' | 'cpw_asc' | 'cpw_desc' | 'wear_desc';

const SORT_OPTIONS: Array<{ key: SortKey; label: string }> = [
  { key: 'recent', label: '默认' },
  { key: 'cpw_asc', label: 'CPW 最低' },
  { key: 'cpw_desc', label: 'CPW 最高' },
  { key: 'wear_desc', label: '穿着最多' },
];

const router = useRouter();
const wardrobeStore = useWardrobeStore();
const lookbookStore = useLookbookStore();
const toast = useToastStore();

const filter = reactive({
  category: 'all',
  color: 'all',
  keyword: '',
  sortBy: 'recent' as SortKey,
});

const filterOpen = ref(false);
const stylingItemId = ref<number | null>(null);

// 面板内的筛选项（色系 + 排序）数量，用于按钮角标
const activeFilterCount = computed(
  () => (filter.color !== 'all' ? 1 : 0) + (filter.sortBy !== 'recent' ? 1 : 0)
);

function effectiveCpw(item: WardrobeItem): number | null {
  if (item.costPerWear != null) return Number(item.costPerWear);
  if (item.price != null && Number(item.price) > 0) {
    return item.wearCount > 0 ? Number(item.price) / item.wearCount : Number(item.price);
  }
  return null;
}

const filteredWardrobe = computed(() => {
  const list = wardrobeStore.items.filter((item) => {
    if (filter.category !== 'all' && item.category !== filter.category) return false;
    if (filter.color !== 'all' && item.color !== filter.color) return false;
    if (filter.keyword.trim()) {
      const kw = filter.keyword.trim().toLowerCase();
      const hay = `${item.name} ${item.color} ${categoryLabel(item.category)} ${(item.styleTags || []).join(' ')}`.toLowerCase();
      if (!hay.includes(kw)) return false;
    }
    return true;
  });

  if (filter.sortBy === 'cpw_asc') {
    return [...list].sort((a, b) => (effectiveCpw(a) ?? 999999) - (effectiveCpw(b) ?? 999999));
  }
  if (filter.sortBy === 'cpw_desc') {
    return [...list].sort((a, b) => (effectiveCpw(b) ?? -1) - (effectiveCpw(a) ?? -1));
  }
  if (filter.sortBy === 'wear_desc') {
    return [...list].sort((a, b) => (b.wearCount || 0) - (a.wearCount || 0));
  }
  return list;
});

const avgCpwText = computed(() => {
  const v = wardrobeStore.preference.avgCostPerWear;
  return v != null ? `¥${Number(v).toFixed(0)}` : null;
});

function resetPanelFilters() {
  filter.color = 'all';
  filter.sortBy = 'recent';
}

function resetFilters() {
  filter.category = 'all';
  filter.keyword = '';
  resetPanelFilters();
}

function openItem(item: WardrobeItem) {
  wardrobeStore.drawerItem = item;
}

async function styleWithAnchor(item: WardrobeItem) {
  if (stylingItemId.value !== null) return;
  stylingItemId.value = item.id;
  lookbookStore.triggerMode = 'anchor';
  lookbookStore.anchorItemId = item.id;
  toast.show(`正在围绕「${item.name}」生成专属搭配…`);
  try {
    await lookbookStore.generateRecommendation({ triggerType: 'anchor_item', anchorItemId: item.id });
    router.push('/lookbook');
    toast.show(`已围绕「${item.name}」生成专属搭配方案`);
  } catch (e: any) {
    toast.show(e?.message || '生成搭配失败，请重试');
  } finally {
    stylingItemId.value = null;
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && filterOpen.value) filterOpen.value = false;
}
onMounted(() => window.addEventListener('keydown', onKeydown));
onUnmounted(() => window.removeEventListener('keydown', onKeydown));
</script>

<template>
  <section class="space-y-4">
    <!-- One-line summary -->
    <div class="flex items-center justify-between gap-3 text-[13px] text-[#666666]">
      <p>
        共 <span class="font-mono text-black">{{ wardrobeStore.items.length }}</span> 件单品
        <template v-if="avgCpwText">
          · 平均 CPW <span class="font-mono text-black">{{ avgCpwText }}</span>
        </template>
        <template v-else> · 录入购入价后可查看 CPW</template>
      </p>
      <button
        @click="router.push({ path: '/profile', query: { tab: 'stats' } })"
        class="shrink-0 hover:text-black transition"
      >
        查看统计 →
      </button>
    </div>

    <!-- Single-row toolbar: category tabs + search + filter -->
    <div class="flex flex-col-reverse gap-3 md:flex-row md:items-center md:gap-4">
      <div class="min-w-0 md:flex-1 overflow-x-auto no-scrollbar">
        <div class="flex items-center gap-1.5 w-max" role="tablist" aria-label="衣物品类">
          <button
            role="tab"
            :aria-selected="filter.category === 'all'"
            @click="filter.category = 'all'"
            class="px-3.5 py-1.5 rounded-full text-[13px] font-medium whitespace-nowrap transition"
            :class="filter.category === 'all' ? 'bg-black text-white' : 'bg-[#F2F2F2] text-[#333333] hover:bg-[#E5E5E5]'"
          >
            全部
          </button>
          <button
            v-for="cat in CATEGORY_OPTIONS"
            :key="cat.key"
            role="tab"
            :aria-selected="filter.category === cat.key"
            @click="filter.category = cat.key"
            class="px-3.5 py-1.5 rounded-full text-[13px] font-medium whitespace-nowrap transition"
            :class="filter.category === cat.key ? 'bg-black text-white' : 'bg-[#F2F2F2] text-[#333333] hover:bg-[#E5E5E5]'"
          >
            {{ cat.label }}
          </button>
        </div>
      </div>

      <div class="flex items-center gap-2 shrink-0">
        <div class="relative flex-1 md:w-48 md:flex-none">
          <AppIcon name="search" :size="15" class="absolute left-3.5 top-1/2 -translate-y-1/2 text-[#666666]" />
          <input
            v-model="filter.keyword"
            type="text"
            placeholder="搜索单品"
            aria-label="搜索单品"
            class="w-full pl-9 pr-8 py-2 rounded-full bg-[#F2F2F2] focus:bg-[#EBEBEB]/70 outline-none text-[13px] transition"
          />
          <button
            v-if="filter.keyword"
            @click="filter.keyword = ''"
            aria-label="清除搜索"
            class="absolute right-3 top-1/2 -translate-y-1/2 text-[#666666] hover:text-black text-sm leading-none"
          >
            ×
          </button>
        </div>

        <button
          @click="filterOpen = true"
          class="inline-flex items-center gap-1.5 px-3.5 py-2 rounded-full bg-[#F2F2F2] hover:bg-[#E5E5E5] text-[12.5px] font-medium text-black transition shrink-0"
        >
          <AppIcon name="sliders" :size="14" />
          筛选与排序
          <span
            v-if="activeFilterCount > 0"
            class="min-w-[18px] h-[18px] px-1 rounded-full bg-black text-white font-mono text-[10.5px] leading-[18px] text-center"
          >
            {{ activeFilterCount }}
          </span>
        </button>
      </div>
    </div>

    <!-- Garment Catalog Grid -->
    <div
      v-if="filteredWardrobe.length > 0"
      class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-x-3 gap-y-5 sm:gap-x-4"
    >
      <div
        v-for="item in filteredWardrobe"
        :key="item.id"
        role="button"
        tabindex="0"
        :aria-label="`查看${item.name}详情`"
        @click="openItem(item)"
        @keydown.enter.self="openItem(item)"
        class="group cursor-pointer flex flex-col focus:outline-none"
      >
        <div
          class="relative aspect-[4/5] rounded-2xl bg-[#F5F5F5] p-4 flex items-center justify-center overflow-hidden ring-black/70 group-focus-visible:ring-2"
        >
          <GarmentVisual
            :image-url="item.processedImageUrl"
            :svg-type="item.svgType"
            :color-hex="item.colorHex"
            :alt="item.name"
            class="transition-transform duration-500 group-hover:scale-105"
          />

          <!-- 桌面：悬停/聚焦时出现的文字胶囊 -->
          <button
            @click.stop="styleWithAnchor(item)"
            :disabled="stylingItemId !== null"
            class="absolute bottom-3 left-1/2 -translate-x-1/2 translate-y-2 whitespace-nowrap px-4 py-2 rounded-full bg-black/90 hover:bg-black text-white text-[12px] font-medium opacity-0 group-hover:opacity-100 group-hover:translate-y-0 group-focus-within:opacity-100 group-focus-within:translate-y-0 [@media(hover:none)]:hidden transition-all duration-200 flex items-center justify-center gap-1.5 shadow-md disabled:opacity-65 disabled:cursor-not-allowed"
            :class="stylingItemId === item.id ? '!opacity-100 !translate-y-0' : ''"
          >
            <AppIcon v-if="stylingItemId === item.id" name="loader" :size="13" class="animate-spin" />
            <AppIcon v-else name="sparkles" :size="13" />
            {{ stylingItemId === item.id ? '生成搭配中…' : '以此单品为核心搭配' }}
          </button>
          <!-- 触屏：没有悬停，常驻一个小圆钮 -->
          <button
            @click.stop="styleWithAnchor(item)"
            :disabled="stylingItemId !== null"
            aria-label="以此单品为核心搭配"
            class="hidden [@media(hover:none)]:flex absolute bottom-2 right-2 w-8 h-8 rounded-full bg-black text-white items-center justify-center shadow-md active:scale-95 transition disabled:opacity-65"
          >
            <AppIcon v-if="stylingItemId === item.id" name="loader" :size="14" class="animate-spin" />
            <AppIcon v-else name="sparkles" :size="14" />
          </button>
        </div>

        <h4 class="pt-2.5 px-0.5 font-medium text-[13px] text-black truncate">{{ item.name }}</h4>
      </div>
    </div>

    <!-- Empty State -->
    <div v-else class="rounded-3xl bg-[#FAFAFA] border border-black/[0.06] py-16 text-center space-y-3">
      <p class="text-[16px] font-semibold text-black">
        {{ wardrobeStore.items.length === 0 ? '你的数字衣橱还是空的' : '暂无符合条件的单品' }}
      </p>
      <p class="text-[13px] text-[#666666]">
        {{ wardrobeStore.items.length === 0 ? '点击下方黑色「+」或立刻使用 AI 录入第一件衣物' : '尝试清除筛选条件，或录入一件新衣物' }}
      </p>
      <div class="flex justify-center gap-2.5 pt-1">
        <button
          v-if="wardrobeStore.items.length > 0"
          @click="resetFilters"
          class="px-4 py-2 rounded-full bg-[#EBEBEB] text-black text-[13px] font-medium"
        >
          重置筛选
        </button>
        <button
          @click="router.push({ path: '/scan', query: { mode: 'item' } })"
          class="px-5 py-2 rounded-full bg-black text-white text-[13px] font-medium"
        >
          + AI 录入衣物
        </button>
      </div>
    </div>

    <!-- Filter & Sort Sheet -->
    <transition name="modal-fade">
      <div
        v-if="filterOpen"
        class="fixed inset-0 !mt-0 z-50 flex items-end sm:items-center justify-center bg-black/25 backdrop-blur-[2px] p-4 pb-24 sm:pb-4"
        @click.self="filterOpen = false"
      >
        <div
          role="dialog"
          aria-modal="true"
          aria-label="筛选与排序"
          class="modal-sheet w-full max-w-[440px] bg-white rounded-[28px] border border-black/[0.08] shadow-float p-5 space-y-5"
        >
          <div class="flex items-center justify-between px-1">
            <span class="text-[15px] font-semibold text-black">筛选与排序</span>
            <button
              @click="filterOpen = false"
              aria-label="关闭"
              class="w-7 h-7 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] flex items-center justify-center text-black"
            >
              <AppIcon name="x" :size="14" />
            </button>
          </div>

          <div class="space-y-2.5">
            <div class="px-1 text-[12.5px] text-[#666666]">排序</div>
            <div class="flex flex-wrap gap-1.5">
              <button
                v-for="opt in SORT_OPTIONS"
                :key="opt.key"
                @click="filter.sortBy = opt.key"
                class="px-3.5 py-1.5 rounded-full text-[13px] font-medium transition"
                :class="filter.sortBy === opt.key ? 'bg-black text-white' : 'bg-[#F2F2F2] text-[#333333] hover:bg-[#E5E5E5]'"
              >
                {{ opt.label }}
              </button>
            </div>
          </div>

          <div class="space-y-2.5">
            <div class="px-1 text-[12.5px] text-[#666666]">色系</div>
            <div class="flex flex-wrap gap-1.5">
              <button
                @click="filter.color = 'all'"
                class="px-3.5 py-1.5 rounded-full text-[13px] font-medium transition"
                :class="filter.color === 'all' ? 'bg-black text-white' : 'bg-[#F2F2F2] text-[#333333] hover:bg-[#E5E5E5]'"
              >
                全部
              </button>
              <button
                v-for="sw in COLOR_SWATCHES"
                :key="sw.name"
                @click="filter.color = filter.color === sw.name ? 'all' : sw.name"
                class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-[13px] font-medium transition"
                :class="filter.color === sw.name ? 'bg-black text-white' : 'bg-[#F2F2F2] text-[#333333] hover:bg-[#E5E5E5]'"
              >
                <span class="w-3 h-3 rounded-full border border-black/15" :style="{ backgroundColor: sw.hex }" />
                {{ sw.name }}
              </button>
            </div>
          </div>

          <div class="flex items-center gap-2.5 pt-1">
            <button
              @click="resetPanelFilters"
              :disabled="activeFilterCount === 0"
              class="px-5 py-3 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] text-[13.5px] font-medium text-black transition disabled:opacity-40 disabled:hover:bg-[#F5F5F5]"
            >
              重置
            </button>
            <button
              @click="filterOpen = false"
              class="flex-1 py-3 rounded-full bg-black hover:bg-[#262626] text-white text-[13.5px] font-medium transition"
            >
              查看 {{ filteredWardrobe.length }} 件
            </button>
          </div>
        </div>
      </div>
    </transition>
  </section>
</template>
