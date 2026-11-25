<script setup lang="ts">
import { computed, reactive } from 'vue';
import { useRouter } from 'vue-router';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useLookbookStore } from '@/stores/lookbook';
import { useToastStore } from '@/stores/toast';
import { CATEGORY_OPTIONS, COLOR_SWATCHES, categoryLabel } from '@/utils/constants';
import type { WardrobeItem } from '@/types';
import AppIcon from '@/components/AppIcon.vue';
import GarmentVisual from '@/components/GarmentVisual.vue';

const router = useRouter();
const wardrobeStore = useWardrobeStore();
const lookbookStore = useLookbookStore();
const toast = useToastStore();

const filter = reactive({
  category: 'all',
  color: 'all',
  keyword: '',
  sortBy: 'recent' as 'recent' | 'cpw_asc' | 'cpw_desc' | 'wear_desc',
});

function countByCategory(key: string) {
  return wardrobeStore.items.filter((i) => i.category === key).length;
}

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

function resetFilters() {
  filter.category = 'all';
  filter.color = 'all';
  filter.keyword = '';
  filter.sortBy = 'recent';
}

async function styleWithAnchor(item: WardrobeItem) {
  lookbookStore.triggerMode = 'anchor';
  lookbookStore.anchorItemId = item.id;
  router.push('/lookbook');
  await lookbookStore.generateRecommendation({ triggerType: 'anchor_item', anchorItemId: item.id });
  toast.show(`已围绕「${item.name}」生成专属搭配方案`);
}
</script>

<template>
  <section class="space-y-6">
    <!-- Indyx × Alta CPW Wardrobe Value Header Strip -->
    <div class="grid grid-cols-2 lg:grid-cols-4 gap-3">
      <div class="rounded-2xl bg-[#FAFAFA] border border-black/[0.06] p-4">
        <div class="text-[11px] font-mono uppercase tracking-wider text-[#666666]">衣橱总价值</div>
        <div class="font-mono text-[22px] font-semibold text-black mt-1">
          ¥{{ Number(wardrobeStore.preference.totalWardrobeValue || 0).toLocaleString('zh-CN', { maximumFractionDigits: 0 }) }}
        </div>
        <div class="text-[11px] text-[#666666] mt-0.5">
          已计价 {{ wardrobeStore.preference.pricedItemCount || 0 }} / {{ wardrobeStore.items.length }} 件
        </div>
      </div>

      <div class="rounded-2xl bg-[#FAFAFA] border border-black/[0.06] p-4">
        <div class="text-[11px] font-mono uppercase tracking-wider text-[#666666]">平均单次成本 (CPW)</div>
        <div class="font-mono text-[22px] font-semibold text-black mt-1">
          {{ wardrobeStore.preference.avgCostPerWear != null ? `¥${Number(wardrobeStore.preference.avgCostPerWear).toFixed(1)}` : '--' }}
        </div>
        <div class="text-[11px] text-[#666666] mt-0.5">购入总价 ÷ 累计穿着次数</div>
      </div>

      <div class="rounded-2xl bg-[#FAFAFA] border border-black/[0.06] p-4">
        <div class="text-[11px] font-mono uppercase tracking-wider text-[#666666]">最值回票价</div>
        <div class="text-[14px] font-semibold text-black mt-1.5 truncate">
          {{ wardrobeStore.preference.bestValueItemName || '暂未录入价格' }}
        </div>
        <div class="font-mono text-[11.5px] text-emerald-700 mt-0.5">
          {{ wardrobeStore.preference.bestValueCpw != null ? `CPW ¥${Number(wardrobeStore.preference.bestValueCpw).toFixed(1)} / 次` : '--' }}
        </div>
      </div>

      <div class="rounded-2xl bg-[#FAFAFA] border border-black/[0.06] p-4">
        <div class="text-[11px] font-mono uppercase tracking-wider text-[#666666]">待唤醒高成本单品</div>
        <div class="text-[14px] font-semibold text-black mt-1.5 truncate">
          {{ wardrobeStore.preference.highestCpwItemName || '暂无闲置单品' }}
        </div>
        <div class="font-mono text-[11.5px] text-[#666666] mt-0.5">
          {{ wardrobeStore.preference.highestCpw != null ? `CPW ¥${Number(wardrobeStore.preference.highestCpw).toFixed(1)} / 次` : `闲置率 ${wardrobeStore.preference.idleRate}%` }}
        </div>
      </div>
    </div>

    <!-- Minimal Alta Search + Category + Sort Controls -->
    <div class="space-y-3.5 pb-4 border-b border-black/[0.08]">
      <div class="flex flex-col md:flex-row md:items-center justify-between gap-3">
        <div class="flex flex-wrap gap-1.5">
          <button
            @click="filter.category = 'all'"
            class="px-3.5 py-1.5 rounded-full text-[13px] font-medium transition flex items-center gap-1.5"
            :class="filter.category === 'all' ? 'bg-black text-white' : 'bg-[#F2F2F2] text-[#333333] hover:bg-[#E5E5E5]'"
          >
            全部 <span class="font-mono text-[11px] opacity-75">{{ wardrobeStore.items.length }}</span>
          </button>
          <button
            v-for="cat in CATEGORY_OPTIONS"
            :key="cat.key"
            @click="filter.category = cat.key"
            class="px-3.5 py-1.5 rounded-full text-[13px] font-medium transition flex items-center gap-1.5"
            :class="filter.category === cat.key ? 'bg-black text-white' : 'bg-[#F2F2F2] text-[#333333] hover:bg-[#E5E5E5]'"
          >
            {{ cat.label }} <span class="font-mono text-[11px] opacity-75">{{ countByCategory(cat.key) }}</span>
          </button>
        </div>

        <div class="flex items-center gap-2">
          <div class="relative flex-1 md:w-56">
            <AppIcon name="search" :size="15" class="absolute left-3.5 top-1/2 -translate-y-1/2 text-[#666666]" />
            <input
              v-model="filter.keyword"
              type="text"
              placeholder="搜单品、风格或颜色..."
              class="w-full pl-9 pr-8 py-2 rounded-full bg-[#F2F2F2] focus:bg-[#EBEBEB]/70 outline-none text-[13px] transition"
            />
            <button
              v-if="filter.keyword"
              @click="filter.keyword = ''"
              class="absolute right-3 top-1/2 -translate-y-1/2 text-[#666666] hover:text-black text-sm"
            >
              ×
            </button>
          </div>

          <select
            v-model="filter.sortBy"
            class="px-3.5 py-2 rounded-full bg-[#F2F2F2] text-[12.5px] font-medium text-black outline-none cursor-pointer"
          >
            <option value="recent">默认排序</option>
            <option value="cpw_asc">CPW 最低（最值）</option>
            <option value="cpw_desc">CPW 最高（待唤醒）</option>
            <option value="wear_desc">穿着次数最多</option>
          </select>
        </div>
      </div>

      <div class="flex flex-wrap items-center justify-between gap-3">
        <div class="flex flex-wrap items-center gap-1.5">
          <button
            @click="filter.color = 'all'"
            class="px-2.5 py-1 rounded-full text-[11.5px] font-medium border transition"
            :class="filter.color === 'all' ? 'bg-black text-white border-black' : 'bg-white text-[#666666] border-black/10'"
          >
            全部色系
          </button>
          <button
            v-for="sw in COLOR_SWATCHES"
            :key="sw.name"
            @click="filter.color = filter.color === sw.name ? 'all' : sw.name"
            class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full border text-[11.5px] transition"
            :class="
              filter.color === sw.name
                ? 'bg-black text-white border-black font-medium'
                : 'bg-white border-black/10 text-[#333333] hover:bg-[#F5F5F5]'
            "
          >
            <span class="w-2.5 h-2.5 rounded-full border border-black/15" :style="{ backgroundColor: sw.hex }" />
            {{ sw.name }}
          </button>
        </div>
        <span class="text-[12px] text-[#666666] font-mono">{{ filteredWardrobe.length }} PIECES</span>
      </div>
    </div>

    <!-- Alta Minimal 4:5 Garment Gallery -->
    <div v-if="filteredWardrobe.length > 0" class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-4 sm:gap-5">
      <div
        v-for="item in filteredWardrobe"
        :key="item.id"
        @click="wardrobeStore.drawerItem = item"
        class="group cursor-pointer flex flex-col"
      >
        <div class="relative aspect-[4/5] rounded-2xl bg-[#F5F5F5] p-5 flex items-center justify-center overflow-hidden">
          <GarmentVisual
            :image-url="item.processedImageUrl"
            :svg-type="item.svgType"
            :color-hex="item.colorHex"
            :alt="item.name"
            class="transition-transform duration-500 group-hover:scale-105"
          />

          <span
            class="absolute top-3 left-3 px-2 py-0.5 rounded-full bg-white/90 font-mono text-[10.5px] text-black"
          >
            穿 {{ item.wearCount }} 次
          </span>

          <span
            v-if="effectiveCpw(item) != null"
            class="absolute top-3 right-3 px-2 py-0.5 rounded-full font-mono text-[10.5px] font-medium"
            :class="
              (effectiveCpw(item) || 0) <= 40
                ? 'bg-black text-white'
                : 'bg-white/90 text-[#333333] border border-black/10'
            "
            title="单次穿着成本 = 购入价 ÷ 穿着次数"
          >
            CPW ¥{{ effectiveCpw(item)!.toFixed(0) }}
          </span>

          <button
            @click.stop="styleWithAnchor(item)"
            class="absolute bottom-3 left-3 right-3 py-2 rounded-full bg-black/90 hover:bg-black text-white text-[12px] font-medium opacity-0 translate-y-2 group-hover:opacity-100 group-hover:translate-y-0 transition-all duration-200 flex items-center justify-center gap-1.5 shadow-md"
          >
            <AppIcon name="sparkles" :size="13" />
            以此单品为核心搭配
          </button>
        </div>

        <div class="pt-2.5 px-0.5 space-y-1">
          <div class="flex items-center justify-between gap-2">
            <h4 class="font-medium text-[13.5px] text-black truncate">{{ item.name }}</h4>
            <span v-if="item.price != null" class="font-mono text-[12px] text-[#666666] shrink-0">
              ¥{{ Number(item.price).toFixed(0) }}
            </span>
          </div>
          <div class="flex items-center justify-between text-[11.5px] text-[#666666]">
            <span class="inline-flex items-center gap-1.5">
              <span>{{ categoryLabel(item.category) }}</span>
              <span>·</span>
              <span class="w-2.5 h-2.5 rounded-full border border-black/15 inline-block" :style="{ backgroundColor: item.colorHex }" />
              <span>{{ item.color }}</span>
            </span>
            <span v-if="item.styleTags?.length" class="truncate max-w-[90px] text-[11px] text-black/45">
              {{ item.styleTags[0] }}
            </span>
          </div>
        </div>
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
  </section>
</template>
