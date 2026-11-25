<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { inspoApi } from '@/api/modules';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useLookbookStore } from '@/stores/lookbook';
import { useToastStore } from '@/stores/toast';
import type { InspoLook, OutfitPlanVo } from '@/types';
import AppIcon from '@/components/AppIcon.vue';
import GarmentVisual from '@/components/GarmentVisual.vue';

const router = useRouter();
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

const plan = computed(() => lookbookStore.currentPlan);
const previewSlots = computed(() => (plan.value?.slots || []).slice(0, 4));

// 天气档 sceneTag 本身就是「多云 16°C · 适宜叠穿」，与上方天气胶囊重复，此时只保留穿着建议
const sceneLine = computed(() => {
  const tip = wardrobeStore.activeWeather.tip;
  const tag = plan.value?.sceneTag?.trim();
  if (!tag || /°C|℃/.test(tag)) return tip;
  return `${tip} · ${tag}`;
});

const todayLabel = computed(() =>
  new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' })
);

function openFavoriteLook(fav: OutfitPlanVo) {
  lookbookStore.loadPlan(fav);
  router.push('/lookbook');
  toast.show(`已在画板加载「${fav.themeTitle}」`);
}
</script>

<template>
  <section class="space-y-10">
    <!-- Hero: Today's Look -->
    <div class="rounded-3xl bg-white border border-black/[0.08] p-5 sm:p-7">
      <div class="grid grid-cols-1 lg:grid-cols-12 gap-6 lg:gap-8">
        <div class="lg:col-span-5 flex flex-col justify-between gap-8">
          <div>
            <div class="flex flex-wrap items-center gap-2 text-[13px] text-[#666666]">
              <span class="font-medium text-black">今日穿搭</span>
              <span class="text-black/20">/</span>
              <span>{{ todayLabel }}</span>
            </div>

            <h2 class="text-[30px] sm:text-[36px] font-semibold tracking-[-0.03em] leading-[1.15] text-black mt-4">
              {{ plan?.themeTitle || '今日穿搭待生成' }}
            </h2>

            <div class="mt-4 inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-[#F5F5F5] text-[12.5px] text-black">
              <AppIcon :name="wardrobeStore.activeWeather.icon || 'cloud-sun'" :size="14" />
              <span class="text-[#666666]">{{ wardrobeStore.activeWeather.city }}</span>
              <span class="font-mono font-medium">{{ wardrobeStore.activeWeather.temp }}°C</span>
              <span class="text-[#666666]">{{ wardrobeStore.activeWeather.condition }}</span>
            </div>

            <p class="text-[13.5px] text-[#666666] mt-3 leading-relaxed">{{ sceneLine }}</p>
          </div>

          <div class="flex items-center justify-between gap-4">
            <div v-if="plan" class="text-[12.5px] text-[#666666]">
              匹配度
              <span class="font-mono text-[22px] font-semibold text-black ml-1.5 align-middle">{{ plan.matchScore }}%</span>
            </div>
            <div v-else />
            <button
              @click="router.push('/lookbook')"
              class="inline-flex items-center gap-2 px-6 py-3 rounded-full bg-black hover:bg-[#262626] text-white text-[14px] font-medium transition active:scale-[0.98]"
            >
              打开平铺画板
              <AppIcon name="arrow-right" :size="15" />
            </button>
          </div>
        </div>

        <div class="lg:col-span-7">
          <div v-if="previewSlots.length" class="flatlay-canvas rounded-2xl p-4 sm:p-5">
            <div class="grid grid-cols-2 sm:grid-cols-4 gap-x-3 gap-y-4">
              <div v-for="(slot, idx) in previewSlots" :key="idx" class="min-w-0">
                <div
                  class="aspect-[4/5] rounded-xl bg-white p-3 flex items-center justify-center border"
                  :class="slot.missing ? 'border-dashed border-black/30' : 'border-black/[0.06]'"
                >
                  <GarmentVisual
                    :image-url="slot.item.processedImageUrl"
                    :svg-type="slot.item.svgType"
                    :color-hex="slot.item.colorHex"
                    :alt="slot.item.name"
                  />
                </div>
                <div class="mt-2 text-[12px] text-black truncate">{{ slot.item.name }}</div>
                <div class="text-[11px] text-[#666666] truncate">
                  {{ slot.missing ? '建议补全' : slot.slotLabel }}
                </div>
              </div>
            </div>
          </div>
          <div
            v-else
            class="h-full min-h-[220px] rounded-2xl bg-[#FAFAFA] flex flex-col items-center justify-center gap-3 text-center px-6"
          >
            <p class="text-[13px] text-[#666666]">衣橱尚为空白，先录入第一件衣物吧</p>
            <button
              @click="router.push({ path: '/scan', query: { mode: 'item' } })"
              class="px-4 py-2 rounded-full bg-white border border-black/[0.12] hover:border-black text-[13px] font-medium text-black transition"
            >
              + AI 录入衣物
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Trending China Inspo -->
    <div v-if="trendingLooks.length > 0" class="space-y-4">
      <div class="flex items-end justify-between gap-3">
        <div>
          <h3 class="text-[16px] font-semibold tracking-tight text-black">今日热度造型 TOP 6</h3>
          <p class="text-[12px] text-[#666666] mt-0.5">中国城市穿搭灵感榜</p>
        </div>
        <button
          @click="router.push('/inspo')"
          class="text-[12.5px] text-[#666666] hover:text-black transition shrink-0"
        >
          查看全部 →
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

    <!-- Recent Favorite Lookbooks -->
    <div class="space-y-4">
      <div class="flex items-end justify-between gap-3">
        <h3 class="text-[16px] font-semibold tracking-tight text-black">近期收藏搭配方案</h3>
        <button
          @click="router.push({ path: '/calendar', query: { tab: 'favorites' } })"
          class="text-[12.5px] text-[#666666] hover:text-black transition shrink-0"
        >
          全部收藏 ({{ lookbookStore.favorites.length }}) →
        </button>
      </div>

      <div v-if="lookbookStore.favorites.length > 0" class="grid grid-cols-1 md:grid-cols-2 gap-3">
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
  </section>
</template>
