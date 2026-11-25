<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { inspoApi } from '@/api/modules';
import { useToastStore } from '@/stores/toast';
import type { InspoBanner, InspoBrandCollection, InspoLook, InspoLookPiece } from '@/types';
import AppIcon from '@/components/AppIcon.vue';
import GarmentVisual from '@/components/GarmentVisual.vue';
import InspoLookModal from '@/components/InspoLookModal.vue';
import InspoPieceModal from '@/components/InspoPieceModal.vue';

const toast = useToastStore();

const activeTab = ref<'for_you' | 'top' | 'brands'>('for_you');
const topGender = ref<'womens' | 'mens'>('womens');
const searchQuery = ref('');
const likedOnly = ref(false);
const loading = ref(false);

const banners = ref<InspoBanner[]>([]);
const looks = ref<InspoLook[]>([]);
const brands = ref<InspoBrandCollection[]>([]);
const likedCount = ref(0);

const activeLook = ref<InspoLook | null>(null);
const activePiece = ref<InspoLookPiece | null>(null);
const failedImgs = ref<Record<string, boolean>>({});

let debounceTimer: ReturnType<typeof setTimeout> | null = null;

async function fetchFeed() {
  loading.value = true;
  try {
    const res = await inspoApi.feed({
      tab: activeTab.value,
      gender: activeTab.value === 'top' ? topGender.value : 'all',
      q: searchQuery.value.trim() || undefined,
      likedOnly: likedOnly.value,
    });
    banners.value = res.banners || [];
    looks.value = res.looks || [];
    brands.value = res.brands || [];
    likedCount.value = res.likedCount || 0;
  } catch (e: any) {
    toast.show(e.message || '加载灵感广场失败');
  } finally {
    loading.value = false;
  }
}

onMounted(fetchFeed);

watch([activeTab, topGender, likedOnly], () => {
  fetchFeed();
});

watch(searchQuery, () => {
  if (debounceTimer) clearTimeout(debounceTimer);
  debounceTimer = setTimeout(fetchFeed, 220);
});

const displayedLooks = computed(() => looks.value);

async function handleToggleLike(lookId: string) {
  try {
    const res = await inspoApi.toggleLike(lookId);
    const target = looks.value.find((l) => l.id === lookId);
    if (target) {
      target.liked = res.liked;
      target.likesCount = res.likesCount;
    }
    if (activeLook.value?.id === lookId) {
      activeLook.value = {
        ...activeLook.value,
        liked: res.liked,
        likesCount: res.likesCount,
      };
    }
    likedCount.value = Math.max(0, likedCount.value + (res.liked ? 1 : -1));
    toast.show(res.liked ? '已收藏到「我的喜欢」' : '已取消喜欢');
  } catch (e: any) {
    toast.show(e.message || '操作失败');
  }
}

function applyBannerFilter(b: InspoBanner) {
  searchQuery.value = b.brandFilter;
  activeTab.value = 'for_you';
}

function openBrandLooks(brand: InspoBrandCollection) {
  searchQuery.value = brand.name.split('/')[0].trim();
  activeTab.value = 'for_you';
}

function markFailed(id: string) {
  failedImgs.value = { ...failedImgs.value, [id]: true };
}
</script>

<template>
  <section class="space-y-5">
    <!-- Alta Sticky Search & Segmented Underline Tabs -->
    <div class="space-y-3">
      <div class="flex items-center gap-3">
        <div class="relative flex-1">
          <AppIcon
            name="search"
            :size="17"
            class="absolute left-4 top-1/2 -translate-y-1/2 text-[#666666]"
          />
          <input
            v-model="searchQuery"
            type="text"
            placeholder="搜博主、单品、造型、品牌或城市（如 上海 / 西装 / 耳饰 / 之禾）..."
            class="w-full h-11 pl-11 pr-9 rounded-full bg-[#F2F2F2] focus:bg-[#EBEBEB]/80 text-[14px] text-black placeholder:text-[#777777] outline-none transition"
          />
          <button
            v-if="searchQuery"
            @click="searchQuery = ''"
            class="absolute right-3.5 top-1/2 -translate-y-1/2 w-5 h-5 rounded-full bg-black/15 hover:bg-black/25 text-black flex items-center justify-center"
          >
            <AppIcon name="x" :size="11" />
          </button>
        </div>

        <button
          @click="likedOnly = !likedOnly"
          :title="likedOnly ? '查看全部灵感' : '只看我喜欢的造型'"
          class="relative w-11 h-11 rounded-full flex items-center justify-center transition shrink-0"
          :class="likedOnly ? 'bg-black text-white' : 'hover:bg-[#F2F2F2] text-black'"
        >
          <AppIcon name="heart" :size="20" :fill="likedOnly ? '#FFFFFF' : 'none'" />
          <span
            v-if="likedCount > 0"
            class="absolute -top-0.5 -right-0.5 min-w-[18px] h-[18px] px-1 rounded-full text-[10px] font-mono font-semibold flex items-center justify-center"
            :class="likedOnly ? 'bg-white text-black border border-black' : 'bg-black text-white'"
          >
            {{ likedCount }}
          </span>
        </button>
      </div>

      <!-- Underline Tabs: 为你推荐 / 热度榜 / 品牌精选 -->
      <div class="flex items-center justify-center border-b border-black/[0.08]">
        <div class="flex w-full max-w-[420px]">
          <button
            @click="activeTab = 'for_you'"
            class="relative flex-1 py-2.5 text-[15px] transition"
            :class="activeTab === 'for_you' ? 'font-medium text-black' : 'font-normal text-[#666666] hover:text-black'"
          >
            为你推荐
            <span
              v-if="activeTab === 'for_you'"
              class="absolute bottom-0 inset-x-[25%] h-[2px] bg-black rounded-full"
            />
          </button>
          <button
            @click="activeTab = 'top'"
            class="relative flex-1 py-2.5 text-[15px] transition"
            :class="activeTab === 'top' ? 'font-medium text-black' : 'font-normal text-[#666666] hover:text-black'"
          >
            热度榜
            <span
              v-if="activeTab === 'top'"
              class="absolute bottom-0 inset-x-[25%] h-[2px] bg-black rounded-full"
            />
          </button>
          <button
            @click="activeTab = 'brands'"
            class="relative flex-1 py-2.5 text-[15px] transition flex items-center justify-center gap-1.5"
            :class="activeTab === 'brands' ? 'font-medium text-black' : 'font-normal text-[#666666] hover:text-black'"
          >
            <span class="hidden sm:inline-flex -space-x-1">
              <span class="w-4 h-4 rounded-full bg-[#111111] text-white text-[8px] flex items-center justify-center border border-white">I</span>
              <span class="w-4 h-4 rounded-full bg-[#666666] text-white text-[8px] flex items-center justify-center border border-white">J</span>
            </span>
            品牌精选
            <span
              v-if="activeTab === 'brands'"
              class="absolute bottom-0 inset-x-[25%] h-[2px] bg-black rounded-full"
            />
          </button>
        </div>
      </div>
    </div>

    <!-- TAB 1: 为你推荐 (Exclusively on 智搭 + 6-Col Borderless Model Wall) -->
    <div v-if="activeTab === 'for_you'" class="space-y-6">
      <!-- Editorial Dual Feature Banners -->
      <div v-if="!searchQuery && !likedOnly" class="space-y-3">
        <div class="flex items-center justify-between">
          <h2 class="text-[14px] font-semibold tracking-tight text-black">智搭独家企划</h2>
          <span class="text-[11.5px] text-[#666666]">点击任意全身造型，探索同款淘宝 &amp; 京东直链</span>
        </div>

        <div class="grid grid-cols-1 sm:grid-cols-2 gap-3.5">
          <button
            v-for="bn in banners"
            :key="bn.id"
            @click="applyBannerFilter(bn)"
            :style="{ background: bn.bgStyle }"
            class="relative h-[168px] sm:h-[188px] rounded-2xl overflow-hidden text-left p-5 flex items-center justify-between group transition hover:shadow-editorial"
          >
            <div class="z-10 max-w-[62%] flex flex-col justify-between h-full">
              <div>
                <div class="text-[10px] font-mono tracking-wider uppercase text-black/55">
                  {{ bn.eyebrow }}
                </div>
                <div class="text-[22px] sm:text-[25px] font-semibold tracking-tight text-black leading-tight mt-1">
                  {{ bn.title }}
                </div>
                <p class="text-[11.5px] text-black/65 mt-1.5 line-clamp-2 leading-relaxed">
                  {{ bn.subtitle }}
                </p>
              </div>
              <div>
                <span
                  class="inline-flex items-center gap-1 px-3.5 py-1.5 rounded-full bg-black text-white text-[11px] font-medium group-hover:scale-105 transition"
                >
                  {{ bn.ctaLabel }}
                  <AppIcon name="arrow-right" :size="12" />
                </span>
              </div>
            </div>

            <div class="w-[38%] h-full flex items-end justify-end overflow-hidden">
              <img
                :src="bn.imageUrl"
                :alt="bn.title"
                class="h-full w-full object-contain object-bottom group-hover:scale-105 transition duration-500"
              />
            </div>
          </button>
        </div>
      </div>

      <!-- Filter Status Banner -->
      <div v-if="searchQuery || likedOnly" class="flex items-center justify-between text-[12.5px] text-[#666666]">
        <span>
          {{ likedOnly ? '我的喜欢' : `搜索「${searchQuery}」` }} · 共找到
          <strong class="text-black font-mono">{{ displayedLooks.length }}</strong> 套造型
        </span>
        <button
          @click="
            searchQuery = '';
            likedOnly = false;
          "
          class="text-black underline"
        >
          清除筛选
        </button>
      </div>

      <!-- 6-Column Borderless Full-Body Model Grid (Matches Alta Image 1) -->
      <div v-if="displayedLooks.length > 0" class="grid grid-cols-3 md:grid-cols-6 gap-x-2.5 gap-y-7">
        <div
          v-for="look in displayedLooks"
          :key="look.id"
          @click="activeLook = look"
          class="group cursor-pointer flex flex-col items-center"
        >
          <div class="relative w-full aspect-[150/285] md:aspect-[170/320] rounded-xl overflow-hidden bg-white flex items-end justify-center pt-1">
            <img
              v-if="!failedImgs[look.id]"
              :src="look.modelImageUrl"
              :alt="look.title"
              loading="lazy"
              @error="markFailed(look.id)"
              class="w-full h-full object-contain object-bottom transition-transform duration-500 group-hover:scale-[1.04]"
            />
            <div v-else class="w-full h-full flex items-center justify-center p-4 bg-[#FAFAFA]">
              <GarmentVisual :svg-type="look.fallbackSvgType" color-hex="#E5E5E5" class="w-24 h-24" />
            </div>

            <!-- Subtle Hover Piece Count Pill -->
            <span
              class="absolute top-2 right-2 px-2 py-0.5 rounded-full bg-[#F5F5F5]/95 backdrop-blur-sm text-[10px] font-mono text-black opacity-0 group-hover:opacity-100 transition"
            >
              {{ look.pieces.length }} 件单品
            </span>
          </div>

          <!-- Minimal Metadata (Creator + Personalized Match Score, Pin + China City) -->
          <div class="mt-2 flex w-full flex-col px-0.5 leading-tight">
            <div class="flex w-full items-center justify-between gap-1">
              <p class="truncate text-[12px] font-medium text-black">{{ look.creatorName }}</p>
              <div class="flex items-center gap-1 shrink-0">
                <span
                  v-if="look.matchScore"
                  :title="look.matchReason || '个性化契合度'"
                  class="text-[10px] font-mono font-medium text-black bg-[#F2F2F2] px-1.5 py-0.5 rounded-full"
                >
                  {{ look.matchScore }}%
                </span>
              </div>
            </div>
            <div class="flex items-center justify-between gap-1 text-black/55 mt-0.5">
              <div class="flex items-center gap-0.5 min-w-0">
                <AppIcon name="map-pin" :size="11" class="shrink-0" />
                <p class="truncate text-[10px]">{{ look.city }}</p>
              </div>
              <span class="text-[10px] font-mono shrink-0">{{ look.viewsText }}</span>
            </div>
            <p v-if="look.matchReason" class="truncate text-[9.5px] text-[#666666] mt-0.5">
              {{ look.matchReason }}
            </p>
          </div>
        </div>
      </div>

      <div v-else-if="!loading" class="py-16 text-center space-y-2">
        <p class="text-[15px] font-medium text-black">暂无匹配的穿搭灵感</p>
        <p class="text-[12.5px] text-[#666666]">试试搜索「西装」「风衣」「上海」「耳饰」或清除筛选</p>
      </div>
    </div>

    <!-- TAB 2: 热度榜 TOP (1:1 Matches User Uploaded Top Leaderboard Screenshot!) -->
    <div v-else-if="activeTab === 'top'" class="space-y-5">
      <!-- Womens / Mens Pill Switcher aligned right -->
      <div class="flex items-center justify-between">
        <span class="text-[12.5px] text-[#666666]">实时人气造型 TOP 榜 · 点击查看单品淘宝/京东链接</span>
        <div class="inline-flex items-center rounded-full bg-[#F2F2F2] p-0.5">
          <button
            @click="topGender = 'womens'"
            class="px-5 py-1.5 rounded-full text-[13px] font-medium transition"
            :class="topGender === 'womens' ? 'bg-black text-white shadow-sm' : 'text-black/70 hover:text-black'"
          >
            女装
          </button>
          <button
            @click="topGender = 'mens'"
            class="px-5 py-1.5 rounded-full text-[13px] font-medium transition"
            :class="topGender === 'mens' ? 'bg-black text-white shadow-sm' : 'text-black/70 hover:text-black'"
          >
            男装
          </button>
        </div>
      </div>

      <!-- 6-Col Ranked Grid with Giant Outlined Numerals -->
      <div class="grid grid-cols-3 md:grid-cols-6 gap-x-2.5 gap-y-8">
        <div
          v-for="(look, idx) in displayedLooks"
          :key="look.id"
          @click="activeLook = look"
          class="group cursor-pointer flex flex-col items-center"
        >
          <div class="relative w-full aspect-[150/285] md:aspect-[170/320] bg-white">
            <!-- Oversized Outlined Rank Number behind transparent model cutout (1:1 Alta Top signature!) -->
            <span
              class="alta-rank-stroke absolute bottom-0 -left-1 z-0 text-[62px] sm:text-[76px] leading-none pointer-events-none select-none"
            >
              {{ look.rank || idx + 1 }}
            </span>

            <div class="relative z-10 w-full h-full flex items-end justify-center pt-1">
              <img
                v-if="!failedImgs[look.id]"
                :src="look.modelImageUrl"
                :alt="look.title"
                loading="lazy"
                @error="markFailed(look.id)"
                class="w-full h-full object-contain object-bottom transition-transform duration-500 group-hover:scale-[1.04]"
              />
              <div v-else class="w-full h-full rounded-xl bg-[#FAFAFA] flex items-center justify-center p-4">
                <GarmentVisual :svg-type="look.fallbackSvgType" color-hex="#E5E5E5" class="w-24 h-24" />
              </div>
            </div>
          </div>

          <div class="mt-2.5 flex w-full flex-col px-0.5 leading-tight">
            <div class="flex w-full items-center justify-between gap-1">
              <p class="truncate text-[12px] font-medium text-black">{{ look.creatorName }}</p>
              <div class="flex items-center gap-0.5 text-black/45 shrink-0">
                <AppIcon name="eye" :size="12" />
                <span class="text-[11px] font-mono">{{ look.viewsText }}</span>
              </div>
            </div>
            <div class="flex items-center gap-0.5 text-black/55 mt-0.5">
              <AppIcon name="map-pin" :size="11" />
              <p class="truncate text-[10px]">{{ look.city }}</p>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- TAB 3: 品牌精选 (China Designers & Urban Brands) -->
    <div v-else class="space-y-4">
      <div class="flex items-center justify-between">
        <p class="text-[13px] text-[#666666]">严选中国设计师与高质感都市极简品牌，支持直达淘宝 / 京东旗舰搜索</p>
      </div>

      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
        <div
          v-for="br in brands"
          :key="br.id"
          class="rounded-3xl bg-white border border-black/[0.08] hover:border-black/30 overflow-hidden transition group flex flex-col justify-between"
        >
          <div>
            <div
              @click="openBrandLooks(br)"
              class="h-48 bg-[#F8F8F8] overflow-hidden cursor-pointer relative flex items-end justify-center pt-3"
            >
              <img
                :src="br.coverImageUrl"
                :alt="br.name"
                class="h-full w-auto object-contain object-bottom group-hover:scale-105 transition duration-500"
              />
              <span class="absolute top-3 left-3 px-2.5 py-1 rounded-full bg-white/95 border border-black/[0.06] text-[11px] font-medium text-black">
                {{ br.origin }}
              </span>
            </div>

            <div class="p-4 space-y-1.5">
              <div class="flex items-baseline justify-between gap-2">
                <h3 class="text-[16px] font-semibold tracking-tight text-black">{{ br.name }}</h3>
                <span class="text-[12px] text-[#666666]">{{ br.cnName }}</span>
              </div>
              <p class="text-[12.5px] text-[#666666] leading-relaxed">{{ br.tagline }}</p>
              <div class="font-mono text-[11.5px] text-black/70 pt-1">参考价位：{{ br.priceBand }}</div>
            </div>
          </div>

          <div class="px-4 pb-4 pt-2 flex items-center gap-2">
            <button
              @click="openBrandLooks(br)"
              class="flex-1 py-2 rounded-full bg-black hover:bg-[#262626] text-white text-[12px] font-medium transition"
            >
              看品牌造型
            </button>
            <a
              :href="br.taobaoFlagshipUrl"
              target="_blank"
              rel="noopener noreferrer"
              class="px-3 py-2 rounded-full bg-[#FFF3ED] hover:bg-[#FF5000] text-[#FF5000] hover:text-white text-[11.5px] font-medium transition"
            >
              淘宝↗
            </a>
            <a
              :href="br.jdFlagshipUrl"
              target="_blank"
              rel="noopener noreferrer"
              class="px-3 py-2 rounded-full bg-[#FEF0F0] hover:bg-[#E1251B] text-[#E1251B] hover:text-white text-[11.5px] font-medium transition"
            >
              京东↗
            </a>
          </div>
        </div>
      </div>
    </div>

    <!-- Layer 2: Look Fullscreen Detail Modal (User Image 2) -->
    <InspoLookModal
      :look="activeLook"
      @close="activeLook = null"
      @toggle-like="handleToggleLike"
      @select-piece="(p) => (activePiece = p)"
    />

    <!-- Layer 3: Piece Detail & China Shop Similar Drawer (User Image 3) -->
    <InspoPieceModal
      :piece="activePiece"
      :creator-name="activeLook?.creatorName || '达人'"
      @back="activePiece = null"
      @close="
        activePiece = null;
        activeLook = null;
      "
    />
  </section>
</template>
