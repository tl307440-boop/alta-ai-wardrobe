<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { inspoApi } from '@/api/modules';
import { useAuthStore } from '@/stores/auth';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useLookbookStore } from '@/stores/lookbook';
import { useToastStore } from '@/stores/toast';
import { categoryBarColor } from '@/utils/constants';
import type { InspoLook, OutfitPlanVo } from '@/types';
import AppIcon from '@/components/AppIcon.vue';
import GarmentVisual from '@/components/GarmentVisual.vue';

const router = useRouter();
const authStore = useAuthStore();
const wardrobeStore = useWardrobeStore();
const lookbookStore = useLookbookStore();
const toast = useToastStore();

const trendingLooks = ref<InspoLook[]>([]);

onMounted(async () => {
  try {
    const res = await inspoApi.feed({ tab: 'top', gender: 'womens' });
    trendingLooks.value = (res.looks || []).slice(0, 6);
  } catch {
    trendingLooks.value = [];
  }
});

const categoryStats = computed(() =>
  (wardrobeStore.preference.categoryStats || []).map((c) => ({
    ...c,
    barColor: categoryBarColor(c.key),
  }))
);

function openFavoriteLook(plan: OutfitPlanVo) {
  lookbookStore.loadPlan(plan);
  router.push('/lookbook');
  toast.show(`已在画板加载「${plan.themeTitle}」`);
}
</script>

<template>
  <section class="space-y-8">
    <!-- Top Split: Minimal Alta Hero + Today's Curated Look -->
    <div class="grid grid-cols-1 lg:grid-cols-12 gap-5">
      <!-- Left 7-Col Minimal Hero -->
      <div class="lg:col-span-7 rounded-3xl bg-[#FAFAFA] border border-black/[0.06] p-7 sm:p-9 flex flex-col justify-between">
        <div>
          <div class="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-white border border-black/[0.08] text-[11.5px] font-medium text-black">
            <span class="w-1.5 h-1.5 rounded-full bg-black" />
            {{ authStore.user?.nickname || '你' }} 的私人 AI 造型画廊
          </div>

          <h2 class="text-3xl sm:text-[40px] font-semibold tracking-[-0.03em] leading-[1.15] text-black mt-5">
            把全球灵感，<br />穿进你的真实衣橱
          </h2>
          <p class="text-[13.5px] text-[#666666] mt-3.5 leading-relaxed max-w-lg">
            灵感广场直连淘宝与京东同款，支持 AI 自动抠图打标、实时天气加权搭配推荐，并用单次穿着成本（CPW）量化每一件单品的真实价值。
          </p>
        </div>

        <div class="flex flex-wrap items-center gap-2.5 mt-7">
          <button
            @click="router.push('/inspo')"
            class="inline-flex items-center gap-2 px-5 py-2.5 rounded-full bg-black hover:bg-[#262626] text-white text-[13.5px] font-medium transition active:scale-[0.98]"
          >
            <AppIcon name="search" :size="15" />
            探索灵感发现
          </button>
          <button
            @click="router.push('/lookbook')"
            class="inline-flex items-center gap-2 px-5 py-2.5 rounded-full bg-white hover:bg-[#F2F2F2] border border-black/15 text-black text-[13.5px] font-medium transition"
          >
            智能搭配画板
          </button>
          <button
            @click="router.push({ path: '/scan', query: { mode: 'item' } })"
            class="inline-flex items-center gap-1.5 px-4 py-2.5 rounded-full text-[13px] font-medium text-[#666666] hover:text-black transition"
          >
            + AI 录入衣物
          </button>
        </div>
      </div>

      <!-- Right 5-Col Today's Curated Look -->
      <div class="lg:col-span-5 rounded-3xl bg-white border border-black/[0.08] p-6 flex flex-col justify-between">
        <div>
          <div class="flex items-center justify-between">
            <span class="text-[11px] font-mono uppercase tracking-wider text-[#666666]">TODAY'S LOOK</span>
            <span class="inline-flex items-center gap-1 font-mono text-[12px] text-black bg-[#F5F5F5] px-2.5 py-0.5 rounded-full">
              <AppIcon name="cloud-sun" :size="13" />
              {{ wardrobeStore.activeWeather.city }} {{ wardrobeStore.activeWeather.temp }}°C
            </span>
          </div>

          <h3 class="text-[20px] font-semibold tracking-tight text-black mt-2.5">
            {{ lookbookStore.currentPlan?.themeTitle || '温柔通勤 · 轻松出门' }}
          </h3>
          <p class="text-[12.5px] text-[#666666] mt-0.5">
            {{ wardrobeStore.activeWeather.condition }} · {{ wardrobeStore.activeWeather.tip }}
          </p>

          <div v-if="lookbookStore.currentPlan?.slots?.length" class="grid grid-cols-4 gap-2.5 mt-5">
            <div
              v-for="(slot, idx) in lookbookStore.currentPlan.slots.slice(0, 4)"
              :key="idx"
              class="aspect-[4/5] rounded-2xl bg-[#F5F5F5] p-2 flex items-center justify-center relative"
            >
              <GarmentVisual
                :image-url="slot.item.processedImageUrl"
                :svg-type="slot.item.svgType"
                :color-hex="slot.item.colorHex"
                :alt="slot.item.name"
              />
              <span
                v-if="slot.missing"
                class="w-2 h-2 rounded-full bg-black absolute top-2 right-2"
                title="建议补全单品"
              />
            </div>
          </div>
          <div v-else class="mt-5 p-4 rounded-2xl bg-[#FAFAFA] text-center text-[12px] text-[#666666]">
            衣橱尚为空白，去录入你的第一件衣物吧
          </div>
        </div>

        <div class="mt-5 pt-4 border-t border-black/[0.06] flex items-center justify-between">
          <div class="text-[12px] text-[#666666]">
            综合契合度
            <span class="font-mono font-semibold text-black ml-1">
              {{ lookbookStore.currentPlan?.matchScore || 96 }}%
            </span>
          </div>
          <button
            @click="router.push('/lookbook')"
            class="inline-flex items-center gap-1 text-[13px] font-medium text-black hover:underline"
          >
            打开平铺画板
            <AppIcon name="arrow-right" :size="14" />
          </button>
        </div>
      </div>
    </div>

    <!-- China Inspo Preview Strip (Alta 6-Col Borderless Wall) -->
    <div v-if="trendingLooks.length > 0" class="space-y-3.5">
      <div class="flex items-center justify-between">
        <div>
          <h3 class="text-[16px] font-semibold tracking-tight text-black">今日热度造型 TOP 6</h3>
          <p class="text-[12px] text-[#666666] mt-0.5">源自中国城市穿搭灵感榜 · 点进查看单品并跳转淘宝/京东同款</p>
        </div>
        <button
          @click="router.push('/inspo')"
          class="inline-flex items-center gap-1 px-3.5 py-1.5 rounded-full bg-[#F5F5F5] hover:bg-black hover:text-white text-[12px] font-medium text-black transition"
        >
          进入灵感发现
          <AppIcon name="arrow-right" :size="13" />
        </button>
      </div>

      <div class="grid grid-cols-3 md:grid-cols-6 gap-x-2.5 gap-y-5">
        <div
          v-for="(look, idx) in trendingLooks"
          :key="look.id"
          @click="router.push('/inspo')"
          class="group cursor-pointer flex flex-col"
        >
          <div class="relative w-full aspect-[150/270] md:aspect-[170/300] bg-white">
            <span class="alta-rank-stroke absolute bottom-0 -left-1 z-0 text-[52px] sm:text-[62px] leading-none pointer-events-none select-none">
              {{ idx + 1 }}
            </span>
            <div class="relative z-10 w-full h-full flex items-end justify-center pt-1">
              <img
                :src="look.modelImageUrl"
                :alt="look.title"
                loading="lazy"
                class="w-full h-full object-contain object-bottom group-hover:scale-[1.04] transition duration-500"
              />
            </div>
          </div>
          <div class="mt-2 px-0.5">
            <div class="flex items-center justify-between text-[11.5px]">
              <span class="font-medium text-black truncate">{{ look.creatorName }}</span>
              <span class="font-mono text-[10.5px] text-black/45">{{ look.viewsText }}</span>
            </div>
            <div class="text-[10px] text-black/55 truncate mt-0.5">{{ look.city }}</div>
          </div>
        </div>
      </div>
    </div>

    <!-- Indyx-Style CPW Wardrobe Asset Analytics + Quick Links -->
    <div class="grid grid-cols-1 lg:grid-cols-12 gap-5">
      <div class="lg:col-span-6 rounded-3xl bg-white border border-black/[0.08] p-6 flex flex-col justify-between space-y-5">
        <div>
          <div class="flex items-center justify-between mb-4">
            <h3 class="text-[16px] font-semibold tracking-tight text-black">数字衣橱资产 · CPW 效率</h3>
            <button @click="router.push('/wardrobe')" class="text-[12px] text-[#666666] hover:text-black transition">
              管理衣橱 →
            </button>
          </div>

          <div class="grid grid-cols-3 gap-2.5 mb-5">
            <div class="p-3.5 rounded-2xl bg-[#FAFAFA]">
              <div class="font-mono text-[20px] font-semibold text-black">
                ¥{{ Number(wardrobeStore.preference.totalWardrobeValue || 0).toLocaleString('zh-CN', { maximumFractionDigits: 0 }) }}
              </div>
              <div class="text-[11px] text-[#666666] mt-0.5">衣橱总价值</div>
            </div>
            <div class="p-3.5 rounded-2xl bg-[#FAFAFA]">
              <div class="font-mono text-[20px] font-semibold text-black">
                {{ wardrobeStore.preference.avgCostPerWear != null ? `¥${Number(wardrobeStore.preference.avgCostPerWear).toFixed(1)}` : '--' }}
              </div>
              <div class="text-[11px] text-[#666666] mt-0.5">平均 CPW / 次</div>
            </div>
            <div class="p-3.5 rounded-2xl bg-[#FAFAFA]">
              <div class="font-mono text-[20px] font-semibold text-black">{{ wardrobeStore.preference.idleRate }}%</div>
              <div class="text-[11px] text-[#666666] mt-0.5">低频闲置率</div>
            </div>
          </div>

          <div class="space-y-2">
            <div class="flex items-center justify-between text-[12px] text-[#666666]">
              <span>品类构成（{{ wardrobeStore.items.length }} 件）</span>
              <span class="font-mono">{{ categoryStats.length }} 类</span>
            </div>
            <div class="w-full h-2 rounded-full overflow-hidden flex bg-[#EBEBEB]">
              <div
                v-for="cat in categoryStats"
                :key="cat.key"
                :style="{ width: cat.percent + '%', backgroundColor: cat.barColor }"
                class="h-full transition-all duration-500"
              />
            </div>
            <div class="flex flex-wrap gap-x-3 gap-y-1 pt-1">
              <span v-for="cat in categoryStats" :key="cat.key" class="inline-flex items-center gap-1.5 text-[11.5px] text-[#666666]">
                <span class="w-2 h-2 rounded-full" :style="{ backgroundColor: cat.barColor }" />
                {{ cat.label }} <span class="font-mono text-black">{{ cat.count }}</span>
              </span>
            </div>
          </div>
        </div>

        <div class="pt-3.5 border-t border-black/[0.06] flex flex-wrap items-center justify-between gap-2 text-[12px] text-[#666666]">
          <span v-if="wardrobeStore.preference.bestValueItemName">
            最值回票价：<strong class="text-black">{{ wardrobeStore.preference.bestValueItemName }}</strong>
            （¥{{ Number(wardrobeStore.preference.bestValueCpw).toFixed(1) }}/次）
          </span>
          <span v-else>为衣物录入价格即可自动开启 CPW 资产分析</span>
        </div>
      </div>

      <!-- Recent Saved Lookbooks -->
      <div class="lg:col-span-6 rounded-3xl bg-white border border-black/[0.08] p-6 flex flex-col justify-between">
        <div>
          <div class="flex items-center justify-between mb-4">
            <h3 class="text-[16px] font-semibold tracking-tight text-black">近期收藏搭配方案</h3>
            <button
              @click="router.push({ path: '/calendar', query: { tab: 'favorites' } })"
              class="text-[12px] text-[#666666] hover:text-black transition"
            >
              全部收藏 ({{ lookbookStore.favorites.length }}) →
            </button>
          </div>

          <div v-if="lookbookStore.favorites.length > 0" class="space-y-3">
            <div
              v-for="look in lookbookStore.favorites.slice(0, 2)"
              :key="look.id"
              @click="openFavoriteLook(look)"
              class="p-3.5 rounded-2xl bg-[#FAFAFA] hover:bg-[#F2F2F2] cursor-pointer transition flex items-center justify-between gap-3"
            >
              <div class="flex items-center gap-2 min-w-0">
                <div class="flex -space-x-2 shrink-0">
                  <div
                    v-for="(s, i) in look.slots.slice(0, 3)"
                    :key="i"
                    class="w-11 h-11 rounded-xl bg-white border border-black/[0.08] p-1 flex items-center justify-center"
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
          <div v-else class="py-10 text-center rounded-2xl bg-[#FAFAFA] text-[12.5px] text-[#666666]">
            暂未收藏方案，去搭配画板生成一套吧
          </div>
        </div>

        <div class="grid grid-cols-2 gap-2.5 mt-5 pt-4 border-t border-black/[0.06]">
          <button
            @click="router.push({ path: '/scan', query: { mode: 'inspiration' } })"
            class="py-2.5 px-3 rounded-full bg-[#F5F5F5] hover:bg-black hover:text-white text-[12.5px] font-medium text-black transition flex items-center justify-center gap-1.5"
          >
            <AppIcon name="scan" :size="14" />
            AI 街拍找同款
          </button>
          <button
            @click="router.push('/calendar')"
            class="py-2.5 px-3 rounded-full bg-[#F5F5F5] hover:bg-black hover:text-white text-[12.5px] font-medium text-black transition flex items-center justify-center gap-1.5"
          >
            <AppIcon name="calendar" :size="14" />
            穿搭日历 ({{ lookbookStore.history.length }})
          </button>
        </div>
      </div>
    </div>
  </section>
</template>
