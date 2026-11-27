<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { inspoApi } from '@/api/modules';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useLookbookStore } from '@/stores/lookbook';
import { useToastStore } from '@/stores/toast';
import type { CollageNode, InspoLook, InspoLookPiece, OutfitPlanVo, PlanSlot } from '@/types';
import AppIcon from '@/components/AppIcon.vue';
import GarmentVisual from '@/components/GarmentVisual.vue';
import InspoLookModal from '@/components/InspoLookModal.vue';
import InspoPieceModal from '@/components/InspoPieceModal.vue';

const router = useRouter();
const wardrobeStore = useWardrobeStore();
const lookbookStore = useLookbookStore();
const toast = useToastStore();

const trendingLooks = ref<InspoLook[]>([]);
const activeLook = ref<InspoLook | null>(null);
const activePiece = ref<InspoLookPiece | null>(null);
const heroView = ref<'tryon' | 'collage'>('tryon');
const wearingToday = ref(false);
const shuffling = ref(false);
const submittingTryOn = ref(false);

onMounted(async () => {
  try {
    const res = await inspoApi.feed({ tab: 'top', gender: 'womens' });
    trendingLooks.value = (res.looks || []).slice(0, 6);
  } catch {
    trendingLooks.value = [];
  }
});

const plan = computed(() => lookbookStore.currentPlan);
const heroSlots = computed(() => (plan.value?.slots || []).slice(0, 5));

const activeTryOnUrl = computed(() => {
  if (!plan.value) return '';
  if (plan.value.tryOnImageUrl) return plan.value.tryOnImageUrl;
  const succeededJob = lookbookStore.tryOnJobs.find(
    (j) => j.planId === plan.value?.id && j.status === 'SUCCEEDED' && !!j.previewImageUrl
  );
  return succeededJob?.previewImageUrl || '';
});

const isTryOnRunning = computed(() => lookbookStore.isPlanGenerating(plan.value?.id) || submittingTryOn.value);

watch(
  () => [plan.value?.id, activeTryOnUrl.value],
  ([, url]) => {
    heroView.value = url ? 'tryon' : 'collage';
  },
  { immediate: true }
);

const todayKey = computed(() => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
});

const alreadyWornToday = computed(() => {
  if (!plan.value) return false;
  return lookbookStore.history.some(
    (h) => h.wearDate === todayKey.value && h.planId === plan.value?.id
  );
});

const todayLabel = computed(() =>
  new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' })
);

/**
 * Warm, human daily greeting driven by live weather, temperature, and weekday.
 */
const warmGreeting = computed(() => {
  const w = wardrobeStore.activeWeather;
  const temp = Number(w.temp ?? 18);
  const cond = w.condition || '';
  const weekday = new Date().toLocaleDateString('zh-CN', { weekday: 'short' });
  const hour = new Date().getHours();

  if (/雨|雪/.test(cond)) {
    return `窗外飘着${cond}，挑双好走又防风的搭配，别让阴天打湿今天的心情。`;
  }
  if (temp <= 11) {
    return hour < 12
      ? `微凉起风的${weekday}早晨，添一件轻暖外套，把舒服和从容穿在身上。`
      : `降温微凉的${weekday}，添一件质感外套护住暖意，慢慢走也很体面。`;
  }
  if (temp >= 25) {
    return `阳光刚刚好的${weekday}，换上轻盈透气的浅色系，自在地出门吧。`;
  }
  if (/晴/.test(cond)) {
    return `晴朗柔和的${weekday}，用松弛的叠穿接住日光与微风，今天也会很顺心。`;
  }
  return `早晚带点微风的${weekday}，用轻盈叠穿刚好接住一整天的温差。`;
});

function matchLevelFor(score?: number | null): { bulbs: number; label: string } {
  const s = Number(score ?? 92);
  if (s >= 94) return { bulbs: 3, label: '今日绝配' };
  if (s >= 88) return { bulbs: 2, label: '默契推荐' };
  return { bulbs: 1, label: '随性可穿' };
}

const heroMatch = computed(() => matchLevelFor(plan.value?.matchScore));

/**
 * Styled full-outfit collage composition (reuses saved collageLayout or natural full-body proportions)
 * so the hero ALWAYS shows one cohesive styled look instead of separate plain boxes.
 */
interface HeroCollagePiece {
  key: string;
  slot: PlanSlot;
  left: string;
  top: string;
  scale: number;
  rotation: number;
  zIndex: number;
  flipX: boolean;
}

const heroCollagePieces = computed<HeroCollagePiece[]>(() => {
  const slots = heroSlots.value;
  if (!slots.length) return [];

  const categoryPresets: Record<string, { x: number; y: number; scale: number; rotation: number; zIndex: number }> = {
    outerwear: { x: 26, y: 40, scale: 1.06, rotation: -4, zIndex: 4 },
    top: { x: 52, y: 31, scale: 0.94, rotation: 2, zIndex: 2 },
    dress: { x: 48, y: 46, scale: 1.12, rotation: -1, zIndex: 3 },
    bottom: { x: 47, y: 68, scale: 1.04, rotation: -1, zIndex: 3 },
    shoes: { x: 75, y: 74, scale: 0.84, rotation: 5, zIndex: 5 },
    bag: { x: 78, y: 36, scale: 0.82, rotation: 5, zIndex: 6 },
    accessory: { x: 22, y: 74, scale: 0.76, rotation: -4, zIndex: 7 },
  };

  const saved: CollageNode[] = (plan.value?.collageLayout || []).filter((n) => !n.labelText);
  const usedCounts: Record<string, number> = {};

  const raw = slots.map((slot, idx) => {
    const count = usedCounts[slot.slotKey] || 0;
    usedCounts[slot.slotKey] = count + 1;
    const preset = categoryPresets[slot.slotKey] || {
      x: 28 + (idx % 3) * 22,
      y: 30 + Math.floor(idx / 3) * 34,
      scale: 0.95,
      rotation: idx % 2 === 0 ? -3 : 3,
      zIndex: idx + 1,
    };
    const matchedSaved =
      saved.find((n) => n.itemId === slot.item.id && n.slotKey === slot.slotKey) ||
      saved.find((n) => n.slotKey === slot.slotKey);

    return {
      key: `${slot.slotKey}-${slot.item.id}-${idx}`,
      slot,
      x: matchedSaved && typeof matchedSaved.x === 'number' ? matchedSaved.x : Math.min(84, Math.max(16, preset.x + count * 7)),
      y: matchedSaved && typeof matchedSaved.y === 'number' ? matchedSaved.y : Math.min(84, Math.max(16, preset.y + count * 6)),
      scale: matchedSaved && typeof matchedSaved.scale === 'number' ? matchedSaved.scale : preset.scale,
      rotation: matchedSaved && typeof matchedSaved.rotation === 'number' ? matchedSaved.rotation : preset.rotation,
      zIndex: matchedSaved && typeof matchedSaved.zIndex === 'number' ? matchedSaved.zIndex : preset.zIndex + count,
      flipX: Boolean(matchedSaved?.flipX),
    };
  });

  // Auto-fit if user saved infinite-canvas coordinates beyond [10..90]
  let minX = Infinity;
  let maxX = -Infinity;
  let minY = Infinity;
  let maxY = -Infinity;
  for (const r of raw) {
    minX = Math.min(minX, r.x);
    maxX = Math.max(maxX, r.x);
    minY = Math.min(minY, r.y);
    maxY = Math.max(maxY, r.y);
  }
  const outOfRange = minX < 10 || maxX > 90 || minY < 10 || maxY > 90;
  let vpMinX = 0;
  let vpMinY = 0;
  let vpSpan = 100;
  let vpScale = 1;
  if (outOfRange) {
    const padX = Math.max(18, (maxX - minX) * 0.24);
    const padY = Math.max(18, (maxY - minY) * 0.24);
    vpSpan = Math.max(100, maxX - minX + padX * 2, maxY - minY + padY * 2);
    const cx = (minX + maxX) / 2;
    const cy = (minY + maxY) / 2;
    vpMinX = cx - vpSpan / 2;
    vpMinY = cy - vpSpan / 2;
    vpScale = Math.max(0.5, Math.min(1, 100 / vpSpan));
  }

  return raw.map((r) => ({
    key: r.key,
    slot: r.slot,
    left: `${((r.x - vpMinX) / vpSpan) * 100}%`,
    top: `${((r.y - vpMinY) / vpSpan) * 100}%`,
    scale: r.scale * vpScale,
    rotation: r.rotation,
    zIndex: r.zIndex,
    flipX: r.flipX,
  }));
});

async function handleWearToday() {
  if (!plan.value || wearingToday.value) return;
  wearingToday.value = true;
  try {
    await lookbookStore.recordToday();
    toast.show('已记入今日穿搭，并同步更新单品穿着次数', '查看日历', () => {
      router.push('/calendar');
    });
  } catch (e: any) {
    toast.show(e?.message || '记录今日穿搭失败');
  } finally {
    wearingToday.value = false;
  }
}

async function handleShuffleOutfit() {
  if (shuffling.value || lookbookStore.loading) return;
  shuffling.value = true;
  try {
    await lookbookStore.shuffleOutfit();
    toast.show('已为你换了一套今日灵感搭配');
  } catch (e: any) {
    toast.show(e?.message || '切换搭配失败');
  } finally {
    shuffling.value = false;
  }
}

async function handleQuickTryOn() {
  if (!plan.value || isTryOnRunning.value) return;
  submittingTryOn.value = true;
  try {
    const job = await lookbookStore.submitTryOnJob({
      stylePreset: 'studio_white',
      generationMode: 'auto',
      useReferencePhoto: !!wardrobeStore.aiConfig.modelReferenceUrl,
    });
    if (job) {
      toast.show('AI 上身试穿已开始后台渲染，完成后自动展示在这里');
    }
  } catch (e: any) {
    toast.show(e?.message || '发起 AI 试穿失败');
  } finally {
    submittingTryOn.value = false;
  }
}

function handlePieceClick(slot: PlanSlot) {
  if (!slot.missing && slot.item?.id > 0) {
    wardrobeStore.drawerItem = slot.item;
  } else {
    router.push({ path: '/lookbook', query: { mode: 'collage', planId: String(plan.value?.id || '') } });
  }
}

function openFavoriteLook(fav: OutfitPlanVo) {
  lookbookStore.loadPlan(fav);
  router.push('/lookbook');
  toast.show(`已在画板加载「${fav.themeTitle}」`);
}

async function handleToggleInspoLike(lookId: string) {
  try {
    const res = await inspoApi.toggleLike(lookId);
    const target = trendingLooks.value.find((l) => l.id === lookId);
    if (target) {
      target.liked = res.liked;
      target.likesCount = res.likesCount;
    }
    if (activeLook.value?.id === lookId) {
      activeLook.value = { ...activeLook.value, liked: res.liked, likesCount: res.likesCount };
    }
    toast.show(res.liked ? '已收藏到「我的喜欢」' : '已取消喜欢');
  } catch (e: any) {
    toast.show(e?.message || '操作失败');
  }
}
</script>

<template>
  <section class="space-y-9">
    <!-- Hero: Today's Look -->
    <div class="rounded-3xl bg-white border border-black/[0.08] p-5 sm:p-7">
      <div class="grid grid-cols-1 lg:grid-cols-12 gap-6 lg:gap-8 items-stretch">
        <!-- Left Copy, Warm Greeting, Lightbulb Match & Primary Actions -->
        <div class="lg:col-span-5 flex flex-col justify-between gap-6">
          <div class="space-y-4">
            <!-- Minimal Date + Weather Row -->
            <div class="flex flex-wrap items-center justify-between gap-2">
              <div class="flex items-center gap-2 text-[13px] text-[#666666]">
                <span class="font-semibold text-black">今日穿搭</span>
                <span class="text-black/20">/</span>
                <span>{{ todayLabel }}</span>
              </div>

              <div class="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-[#F5F5F5] text-[12px] text-black">
                <AppIcon :name="wardrobeStore.activeWeather.icon || 'cloud-sun'" :size="13" />
                <span class="text-[#666666]">{{ wardrobeStore.activeWeather.city }}</span>
                <span class="font-mono font-medium">{{ wardrobeStore.activeWeather.temp }}°C</span>
                <span class="text-[#666666]">{{ wardrobeStore.activeWeather.condition }}</span>
              </div>
            </div>

            <!-- Warm Human Daily Greeting -->
            <p class="text-[20px] sm:text-[24px] font-medium tracking-[-0.02em] leading-[1.45] text-black pt-1">
              {{ warmGreeting }}
            </p>
          </div>

          <!-- Desktop Bottom Bar: Match Lightbulbs + Wear/Shuffle CTAs -->
          <div class="hidden lg:flex flex-col gap-5 pt-4 border-t border-black/[0.06]">
            <div
              v-if="plan"
              class="flex items-center justify-between gap-3"
              :title="`综合匹配度 ${plan.matchScore}%`"
            >
              <div class="inline-flex items-center gap-2">
                <span class="text-[12.5px] text-[#666666]">默契度</span>
                <span class="inline-flex items-center gap-1" :aria-label="`默契度 ${heroMatch.bulbs} 级 · ${heroMatch.label}`">
                  <AppIcon
                    v-for="n in 3"
                    :key="n"
                    name="lightbulb"
                    :size="17"
                    :fill="n <= heroMatch.bulbs ? '#FBBF24' : 'none'"
                    :class="n <= heroMatch.bulbs ? 'text-black' : 'text-black/20'"
                  />
                </span>
                <span class="text-[12.5px] font-medium text-black">{{ heroMatch.label }}</span>
              </div>
            </div>

            <div class="flex items-center gap-2.5">
              <button
                type="button"
                @click="handleShuffleOutfit"
                :disabled="shuffling || lookbookStore.loading"
                class="inline-flex items-center justify-center gap-1.5 px-5 py-3 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] text-black text-[13.5px] font-medium transition active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <AppIcon name="refresh" :size="15" :class="shuffling ? 'animate-spin' : ''" />
                {{ shuffling ? '换搭中…' : '换一换' }}
              </button>
              <button
                type="button"
                @click="handleWearToday"
                :disabled="!plan || wearingToday"
                class="flex-1 inline-flex items-center justify-center gap-1.5 px-6 py-3 rounded-full bg-black hover:bg-[#262626] text-white text-[14px] font-medium transition active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <AppIcon v-if="wearingToday" name="loader" :size="15" class="animate-spin" />
                <AppIcon v-else :name="alreadyWornToday ? 'check' : 'sparkles'" :size="15" />
                {{ wearingToday ? '记录中…' : alreadyWornToday ? '今日已穿这套' : '就穿这套' }}
              </button>
            </div>
          </div>
        </div>

        <!-- Right Visual: One Styled Outfit Hero Visual + Individual Piece Strip Below -->
        <div class="lg:col-span-7 flex flex-col gap-3.5">
          <template v-if="heroSlots.length > 0">
            <!-- Main Styled Outfit Frame (AI Model Try-On OR Composed Outfit Collage) -->
            <div class="relative rounded-2xl overflow-hidden border border-black/[0.06] flatlay-canvas aspect-[4/4.3] sm:aspect-[16/11] w-full flex items-center justify-center">
              <!-- State A: AI Virtual Model Try-On Image -->
              <template v-if="heroView === 'tryon' && activeTryOnUrl">
                <img
                  :src="activeTryOnUrl"
                  alt="今日穿搭全身试穿图"
                  class="w-full h-full object-contain bg-[#FAFAFA]"
                />
              </template>

              <!-- State B: Full-Outfit Composed Collage -->
              <template v-else>
                <div class="relative w-full h-full">
                  <div
                    v-for="piece in heroCollagePieces"
                    :key="piece.key"
                    @click="handlePieceClick(piece.slot)"
                    class="absolute cursor-pointer transition-transform duration-300 hover:scale-[1.04]"
                    :style="{
                      left: piece.left,
                      top: piece.top,
                      zIndex: piece.zIndex,
                      transform: `translate(-50%, -50%) scale(${piece.scale}) rotate(${piece.rotation}deg)`,
                    }"
                  >
                    <div
                      class="w-28 h-28 sm:w-36 sm:h-36 flex items-center justify-center"
                      :style="{ transform: piece.flipX ? 'scaleX(-1)' : 'none' }"
                    >
                      <GarmentVisual
                        :image-url="piece.slot.item.processedImageUrl"
                        :svg-type="piece.slot.item.svgType"
                        :color-hex="piece.slot.item.colorHex"
                        :alt="piece.slot.item.name"
                        class="w-full h-full drop-shadow-[0_10px_18px_rgba(0,0,0,0.10)]"
                      />
                    </div>
                  </div>
                </div>
              </template>

              <!-- Floating Subtle View Switcher / Quick Try-On Pill -->
              <div class="absolute top-3 right-3 z-20 flex items-center gap-1.5">
                <div
                  v-if="activeTryOnUrl"
                  class="inline-flex p-0.5 rounded-full bg-white/90 backdrop-blur-md border border-black/[0.08] text-[11px] shadow-sm"
                >
                  <button
                    type="button"
                    @click="heroView = 'tryon'"
                    class="px-2.5 py-1 rounded-full font-medium transition"
                    :class="heroView === 'tryon' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
                  >
                    模特上身
                  </button>
                  <button
                    type="button"
                    @click="heroView = 'collage'"
                    class="px-2.5 py-1 rounded-full font-medium transition"
                    :class="heroView === 'collage' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
                  >
                    整套平铺
                  </button>
                </div>

                <button
                  v-else
                  type="button"
                  @click="handleQuickTryOn"
                  :disabled="isTryOnRunning"
                  class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-white/92 hover:bg-black hover:text-white backdrop-blur-md border border-black/[0.08] text-[11.5px] font-medium text-black shadow-sm transition disabled:opacity-75 disabled:cursor-not-allowed"
                >
                  <AppIcon v-if="submittingTryOn" name="loader" :size="12" class="animate-spin" />
                  <span v-else-if="isTryOnRunning" class="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse" />
                  <AppIcon v-else name="sparkles" :size="12" />
                  {{ isTryOnRunning ? 'AI 试穿生成中…' : 'AI 上身试穿' }}
                </button>
              </div>
            </div>

            <!-- Individual Pieces Strip Directly Below Main Visual -->
            <div class="flex gap-2.5 overflow-x-auto no-scrollbar pb-0.5 sm:grid sm:grid-cols-5 sm:overflow-visible">
              <button
                v-for="(slot, idx) in heroSlots"
                :key="`${slot.slotKey}-${slot.item.id}-${idx}`"
                type="button"
                @click="handlePieceClick(slot)"
                class="group w-[86px] sm:w-auto shrink-0 text-left focus:outline-none"
              >
                <div
                  class="aspect-square rounded-xl bg-[#FAFAFA] group-hover:bg-[#F2F2F2] p-2 flex items-center justify-center border transition"
                  :class="slot.missing ? 'border-dashed border-black/25' : 'border-black/[0.06]'"
                >
                  <GarmentVisual
                    :image-url="slot.item.processedImageUrl"
                    :svg-type="slot.item.svgType"
                    :color-hex="slot.item.colorHex"
                    :alt="slot.item.name"
                    class="w-full h-full transition-transform duration-300 group-hover:scale-105"
                  />
                </div>
                <div class="mt-1.5 text-[11.5px] text-black truncate px-0.5">{{ slot.item.name }}</div>
                <div class="text-[10.5px] text-[#777777] truncate px-0.5">
                  {{ slot.missing ? '建议补全' : slot.slotLabel }}
                </div>
              </button>
            </div>
          </template>

          <div
            v-else
            class="h-full min-h-[240px] rounded-2xl bg-[#FAFAFA] flex flex-col items-center justify-center gap-3 text-center px-6"
          >
            <p class="text-[13px] text-[#666666]">衣橱尚为空白，先录入第一件衣物吧</p>
            <button
              @click="router.push({ path: '/scan', query: { mode: 'item' } })"
              class="px-4 py-2 rounded-full bg-white border border-black/[0.12] hover:border-black text-[13px] font-medium text-black transition"
            >
              + AI 录入衣物
            </button>
          </div>

          <!-- Mobile Bottom Action Bar (Lightbulbs + Shuffle + Wear Today) -->
          <div class="flex lg:hidden flex-col gap-3 pt-2 border-t border-black/[0.06]">
            <div v-if="plan" class="flex items-center justify-between" :title="`综合匹配度 ${plan.matchScore}%`">
              <div class="inline-flex items-center gap-1.5">
                <span class="text-[12px] text-[#666666]">默契度</span>
                <span class="inline-flex items-center gap-0.5">
                  <AppIcon
                    v-for="n in 3"
                    :key="n"
                    name="lightbulb"
                    :size="16"
                    :fill="n <= heroMatch.bulbs ? '#FBBF24' : 'none'"
                    :class="n <= heroMatch.bulbs ? 'text-black' : 'text-black/20'"
                  />
                </span>
                <span class="text-[12px] font-medium text-black ml-0.5">{{ heroMatch.label }}</span>
              </div>
              <span class="text-[11.5px] text-[#777777]">共 {{ heroSlots.length }} 件单品</span>
            </div>

            <div class="flex items-center gap-2.5">
              <button
                type="button"
                @click="handleShuffleOutfit"
                :disabled="shuffling || lookbookStore.loading"
                class="inline-flex items-center justify-center gap-1.5 px-4 py-2.5 rounded-full bg-[#F5F5F5] active:bg-[#EBEBEB] text-black text-[13.5px] font-medium transition disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <AppIcon name="refresh" :size="15" :class="shuffling ? 'animate-spin' : ''" />
                {{ shuffling ? '换搭中…' : '换一换' }}
              </button>
              <button
                type="button"
                @click="handleWearToday"
                :disabled="!plan || wearingToday"
                class="flex-1 inline-flex items-center justify-center gap-1.5 px-5 py-2.5 rounded-full bg-black active:bg-[#262626] text-white text-[13.5px] font-medium transition disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <AppIcon v-if="wearingToday" name="loader" :size="15" class="animate-spin" />
                <AppIcon v-else :name="alreadyWornToday ? 'check' : 'sparkles'" :size="15" />
                {{ wearingToday ? '记录中…' : alreadyWornToday ? '今日已穿这套' : '就穿这套' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Outfit Inspiration: Single Row on Mobile (Horizontal Peek Scroll) & 6-Col Row on Desktop -->
    <div v-if="trendingLooks.length > 0" class="space-y-4">
      <div class="flex items-center justify-between gap-3">
        <h3 class="text-[16px] font-semibold tracking-tight text-black">穿搭灵感</h3>
        <button
          @click="router.push('/inspo')"
          class="text-[12.5px] text-[#666666] hover:text-black transition shrink-0"
        >
          查看全部 →
        </button>
      </div>

      <div
        class="flex md:grid md:grid-cols-6 gap-3 overflow-x-auto md:overflow-visible snap-x snap-mandatory no-scrollbar -mx-4 px-4 md:mx-0 md:px-0"
      >
        <div
          v-for="look in trendingLooks"
          :key="look.id"
          @click="activeLook = look"
          class="group cursor-pointer flex flex-col w-[35vw] min-w-[116px] max-w-[144px] md:w-auto md:min-w-0 md:max-w-none shrink-0 snap-start"
        >
          <div class="relative w-full aspect-[150/255] md:aspect-[170/290] bg-[#FAFAFA] rounded-2xl overflow-hidden flex items-end justify-center pt-2 px-1">
            <img
              :src="look.modelImageUrl"
              :alt="look.title"
              loading="lazy"
              class="w-full h-full object-contain object-bottom group-hover:scale-[1.04] transition duration-500"
            />
          </div>
          <div class="mt-2 px-0.5">
            <div class="flex items-center justify-between text-[11.5px]">
              <span class="font-medium text-black truncate">{{ look.creatorName }}</span>
              <span class="font-mono text-[10.5px] text-black/45">{{ look.viewsText }}</span>
            </div>
            <div class="text-[10.5px] text-black/50 truncate mt-0.5">{{ look.city }}</div>
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
              <div class="mt-0.5 flex items-center gap-1.5 text-[11.5px] text-[#666666]">
                <span class="inline-flex items-center gap-0.5">
                  <AppIcon
                    v-for="n in 3"
                    :key="n"
                    name="lightbulb"
                    :size="12"
                    :fill="n <= matchLevelFor(look.matchScore).bulbs ? '#FBBF24' : 'none'"
                    :class="n <= matchLevelFor(look.matchScore).bulbs ? 'text-black' : 'text-black/20'"
                  />
                </span>
                <span class="truncate">{{ matchLevelFor(look.matchScore).label }}</span>
              </div>
            </div>
          </div>
          <AppIcon name="chevron-right" :size="16" class="text-[#666666] shrink-0" />
        </div>
      </div>
      <div v-else class="py-10 text-center rounded-2xl bg-[#FAFAFA] text-[12.5px] text-[#666666]">
        暂未收藏方案，去底部画板搭一套吧
      </div>
    </div>

    <!-- Direct Look & Piece Modals from Home Inspiration Strip -->
    <InspoLookModal
      :look="activeLook"
      @close="activeLook = null"
      @toggle-like="handleToggleInspoLike"
      @select-piece="(piece) => (activePiece = piece)"
    />
    <InspoPieceModal
      :piece="activePiece"
      :creator-name="activeLook?.creatorName || ''"
      @back="activePiece = null"
      @close="
        activePiece = null;
        activeLook = null;
      "
    />
  </section>
</template>
