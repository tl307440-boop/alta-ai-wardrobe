<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useToastStore } from '@/stores/toast';
import type { InspoLookPiece, InspoSimilarProduct } from '@/types';
import AppIcon from './AppIcon.vue';
import GarmentVisual from './GarmentVisual.vue';

const props = defineProps<{
  piece: InspoLookPiece | null;
  creatorName: string;
}>();

const emit = defineEmits<{
  (e: 'back'): void;
  (e: 'close'): void;
}>();

const router = useRouter();
const wardrobeStore = useWardrobeStore();
const toast = useToastStore();

const platformFilter = ref<'all' | 'taobao' | 'jd'>('all');
const savedSimIds = ref<string[]>([]);
const adding = ref(false);
const failedImages = ref<Record<string, boolean>>({});

const filteredSimilars = computed(() => {
  const list = props.piece?.similarItems || [];
  if (platformFilter.value === 'all') return list;
  return list.filter((item) => item.platform === platformFilter.value);
});

function toggleSaveSim(id: string) {
  const idx = savedSimIds.value.indexOf(id);
  if (idx > -1) {
    savedSimIds.value.splice(idx, 1);
  } else {
    savedSimIds.value.push(id);
    toast.show('已收藏该相似单品至心愿清单');
  }
}

function isRealImg(url?: string, key?: string) {
  if (!url || url.startsWith('svg://')) return false;
  if (key && failedImages.value[key]) return false;
  return true;
}

function markImgFailed(key: string) {
  failedImages.value = { ...failedImages.value, [key]: true };
}

async function addPieceToCloset() {
  if (!props.piece) return;
  adding.value = true;
  try {
    await wardrobeStore.createItem({
      name: `${props.piece.brand} ${props.piece.name}`.slice(0, 24),
      category: props.piece.category || 'accessory',
      color: props.piece.color || '纯白',
      colorHex: props.piece.colorHex || '#F5F5F5',
      seasons: ['春', '秋'],
      styleTags: ['灵感同款', props.piece.slotLabel || '极简通勤'],
      svgType: props.piece.svgType || 'crossbody_bag',
      processedImageUrl: props.piece.imageUrl || `svg://${props.piece.svgType || 'trench'}`,
      sourceType: 'screenshot',
      price: Number(props.piece.price) || undefined,
    });
    toast.show(`已将「${props.piece.name}」加入衣橱并计入 CPW`, '查看衣橱', () => {
      emit('close');
      router.push('/wardrobe');
    });
  } catch (e: any) {
    toast.show(e.message || '加入衣橱失败');
  } finally {
    adding.value = false;
  }
}

function openExternalBuy(sim: InspoSimilarProduct, prefer?: 'taobao' | 'jd') {
  const url = prefer === 'taobao' ? sim.taobaoUrl : prefer === 'jd' ? sim.jdUrl : sim.buyUrl;
  window.open(url, '_blank', 'noopener,noreferrer');
}
</script>

<template>
  <transition name="modal-fade">
    <div
      v-if="piece"
      class="fixed inset-0 z-[55] flex items-center justify-center bg-black/40 backdrop-blur-[3px] p-0 sm:p-5"
      @click.self="emit('close')"
    >
      <div
        class="modal-sheet relative w-full h-full sm:h-[88vh] sm:max-w-[780px] bg-white sm:rounded-[28px] shadow-float flex flex-col overflow-hidden"
      >
        <!-- Top Header: "<" + "From XX's closet" + Close -->
        <div class="relative flex items-center justify-center px-5 h-14 border-b border-black/[0.06] shrink-0">
          <button
            @click="emit('back')"
            title="返回全身造型"
            class="absolute left-4 w-9 h-9 rounded-full bg-white hover:bg-[#F5F5F5] border border-black/[0.08] shadow-sm flex items-center justify-center text-black transition"
          >
            <AppIcon name="chevron-left" :size="18" />
          </button>

          <span class="text-[14px] font-semibold tracking-tight text-black">
            来自 {{ creatorName }} 的衣橱
          </span>

          <button
            @click="emit('close')"
            title="关闭"
            class="absolute right-4 w-9 h-9 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] flex items-center justify-center text-black transition"
          >
            <AppIcon name="x" :size="16" />
          </button>
        </div>

        <!-- Scrollable Body -->
        <div class="flex-1 overflow-y-auto px-5 sm:px-8 py-6 space-y-6">
          <!-- Hero Piece Showcase (Matches Alta Image 3 Top Area) -->
          <div class="grid grid-cols-1 sm:grid-cols-12 gap-6 items-center pb-6 border-b border-black/[0.08]">
            <div class="sm:col-span-5 flex items-center justify-center">
              <div
                class="w-full max-w-[240px] aspect-square rounded-2xl bg-[#FAFAFA] flex items-center justify-center overflow-hidden p-4"
              >
                <img
                  v-if="isRealImg(piece.imageUrl, 'hero-' + piece.id)"
                  :src="piece.imageUrl"
                  :alt="piece.name"
                  @error="markImgFailed('hero-' + piece.id)"
                  class="w-full h-full object-contain mix-blend-multiply"
                />
                <GarmentVisual
                  v-else
                  :image-url="piece.imageUrl"
                  :svg-type="piece.svgType"
                  :color-hex="piece.colorHex"
                  :alt="piece.name"
                  class="w-40 h-40"
                />
              </div>
            </div>

            <div class="sm:col-span-7 space-y-3">
              <div class="flex items-center gap-2">
                <span class="px-2.5 py-0.5 rounded-full bg-[#F5F5F5] text-[11px] text-[#666666]">
                  {{ piece.slotLabel }}
                </span>
                <span class="inline-flex items-center gap-1 text-[11.5px] text-[#666666]">
                  <span
                    class="w-2.5 h-2.5 rounded-full border border-black/15"
                    :style="{ backgroundColor: piece.colorHex }"
                  />
                  {{ piece.color }}
                </span>
              </div>

              <div>
                <h3 class="text-[20px] font-semibold tracking-tight text-black uppercase">
                  {{ piece.brand }}
                </h3>
                <p class="text-[15px] text-[#666666] mt-0.5">{{ piece.name }}</p>
              </div>

              <div class="font-mono text-[22px] font-semibold text-black">
                ¥{{ Number(piece.price).toFixed(0) }}
                <span class="text-[11.5px] font-sans font-normal text-[#666666] ml-1">参考专柜 / 旗舰价</span>
              </div>

              <!-- Direct China E-commerce Links: Taobao + JD -->
              <div class="flex flex-wrap items-center gap-2.5 pt-1">
                <a
                  :href="piece.taobaoUrl"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="inline-flex items-center gap-1.5 px-4 py-2 rounded-full bg-[#FF5000] hover:bg-[#e64800] text-white text-[12.5px] font-medium transition shadow-sm"
                >
                  去淘宝搜同款
                  <AppIcon name="external-link" :size="13" />
                </a>
                <a
                  :href="piece.jdUrl"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="inline-flex items-center gap-1.5 px-4 py-2 rounded-full bg-[#E1251B] hover:bg-[#c81f16] text-white text-[12.5px] font-medium transition shadow-sm"
                >
                  去京东搜同款
                  <AppIcon name="external-link" :size="13" />
                </a>
                <button
                  @click="addPieceToCloset"
                  :disabled="adding"
                  class="inline-flex items-center gap-1.5 px-4 py-2 rounded-full bg-white hover:bg-black hover:text-white border border-black/15 text-black text-[12.5px] font-medium transition disabled:opacity-55 disabled:cursor-not-allowed"
                >
                  <AppIcon v-if="adding" name="loader" :size="13" class="animate-spin" />
                  <AppIcon v-else name="plus" :size="13" />
                  {{ adding ? '录入中...' : '收入我的衣橱' }}
                </button>
              </div>
            </div>
          </div>

          <!-- Shop Similar Items (Matches Alta Image 3 4-Column Grid) -->
          <div class="space-y-4">
            <div class="flex flex-wrap items-center justify-between gap-2">
              <div class="flex items-center gap-2 text-[14px] font-semibold text-black">
                <AppIcon name="shopping-bag" :size="16" />
                <span>选购相似同款</span>
                <span class="text-[12px] font-normal text-[#666666]">（点击直达淘宝 / 京东）</span>
              </div>

              <div class="inline-flex items-center p-0.5 rounded-full bg-[#F2F2F2] text-[12px]">
                <button
                  @click="platformFilter = 'all'"
                  class="px-3 py-1 rounded-full transition"
                  :class="platformFilter === 'all' ? 'bg-black text-white font-medium' : 'text-[#666666]'"
                >
                  全部
                </button>
                <button
                  @click="platformFilter = 'taobao'"
                  class="px-3 py-1 rounded-full transition"
                  :class="platformFilter === 'taobao' ? 'bg-[#FF5000] text-white font-medium' : 'text-[#666666]'"
                >
                  淘宝
                </button>
                <button
                  @click="platformFilter = 'jd'"
                  class="px-3 py-1 rounded-full transition"
                  :class="platformFilter === 'jd' ? 'bg-[#E1251B] text-white font-medium' : 'text-[#666666]'"
                >
                  京东
                </button>
              </div>
            </div>

            <div class="grid grid-cols-2 sm:grid-cols-4 gap-3.5">
              <div
                v-for="sim in filteredSimilars"
                :key="sim.id"
                @click="openExternalBuy(sim)"
                class="group cursor-pointer flex flex-col"
              >
                <div class="relative aspect-square rounded-2xl bg-[#F5F5F5] overflow-hidden flex items-center justify-center p-3">
                  <img
                    v-if="isRealImg(sim.imageUrl, sim.id)"
                    :src="sim.imageUrl"
                    :alt="sim.title"
                    @error="markImgFailed(sim.id)"
                    class="w-full h-full object-cover rounded-xl group-hover:scale-105 transition duration-300"
                  />
                  <GarmentVisual
                    v-else
                    :image-url="sim.imageUrl"
                    :svg-type="sim.svgType"
                    :color-hex="sim.colorHex"
                    :alt="sim.title"
                    class="w-24 h-24 group-hover:scale-105 transition duration-300"
                  />

                  <!-- Top-Right Save Heart (Alta style) -->
                  <button
                    @click.stop="toggleSaveSim(sim.id)"
                    class="absolute top-2.5 right-2.5 w-7 h-7 rounded-full bg-white/85 backdrop-blur-sm flex items-center justify-center transition hover:scale-110"
                    title="收藏相似款"
                  >
                    <AppIcon
                      name="heart"
                      :size="14"
                      :fill="savedSimIds.includes(sim.id) ? '#000000' : 'none'"
                      class="text-black"
                    />
                  </button>

                  <!-- Bottom-Left China Platform Pill -->
                  <span
                    class="absolute bottom-2 left-2 px-2 py-0.5 rounded-full text-[10px] font-medium text-white shadow-sm"
                    :class="sim.platform === 'jd' ? 'bg-[#E1251B]' : 'bg-[#FF5000]'"
                  >
                    {{ sim.platformLabel }}
                  </span>
                </div>

                <!-- Brand + Price (Alta clean typography) -->
                <div class="pt-2 px-0.5 space-y-0.5">
                  <div class="text-[12.5px] font-medium text-black truncate group-hover:underline">
                    {{ sim.brand }}
                  </div>
                  <div class="text-[11px] text-[#666666] truncate">{{ sim.title }}</div>
                  <div class="flex items-center gap-1.5 pt-0.5 font-mono text-[12px]">
                    <span class="font-semibold text-black">¥{{ Number(sim.price).toFixed(0) }}</span>
                    <span
                      v-if="sim.originalPrice"
                      class="text-[11px] text-[#999999] line-through"
                    >
                      ¥{{ Number(sim.originalPrice).toFixed(0) }}
                    </span>
                  </div>
                  <!-- Quick Dual Platform Micro-Links -->
                  <div class="flex items-center gap-2 pt-1 text-[10.5px]">
                    <button
                      @click.stop="openExternalBuy(sim, 'taobao')"
                      class="text-[#FF5000] hover:underline font-medium"
                    >
                      淘宝↗
                    </button>
                    <span class="text-black/15">|</span>
                    <button
                      @click.stop="openExternalBuy(sim, 'jd')"
                      class="text-[#E1251B] hover:underline font-medium"
                    >
                      京东↗
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </transition>
</template>
