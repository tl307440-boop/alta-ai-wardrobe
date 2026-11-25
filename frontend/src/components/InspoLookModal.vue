<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useLookbookStore } from '@/stores/lookbook';
import { useToastStore } from '@/stores/toast';
import type { InspoLook, InspoLookPiece } from '@/types';
import AppIcon from './AppIcon.vue';
import GarmentVisual from './GarmentVisual.vue';

const props = defineProps<{
  look: InspoLook | null;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'toggle-like', lookId: string): void;
  (e: 'select-piece', piece: InspoLookPiece): void;
}>();

const router = useRouter();
const lookbookStore = useLookbookStore();
const toast = useToastStore();

const menuOpen = ref(false);
const commentsOpen = ref(false);
const commentInput = ref('');
const localComments = ref<string[]>([]);
const failedPieceImgs = ref<Record<string, boolean>>({});
const modelImgFailed = ref(false);

watch(
  () => props.look,
  (val) => {
    menuOpen.value = false;
    commentsOpen.value = false;
    commentInput.value = '';
    modelImgFailed.value = false;
    localComments.value = val?.sampleComments ? [...val.sampleComments] : [];
  },
  { immediate: true }
);

function submitComment() {
  const txt = commentInput.value.trim();
  if (!txt) return;
  localComments.value.unshift(`我：${txt}`);
  commentInput.value = '';
  toast.show('评论已发布');
}

async function restyleWithMyCloset() {
  if (!props.look) return;
  const query = `参考「${props.look.creatorName}」的${props.look.styleTag}造型（${props.look.title}），优先用我衣橱已有单品搭配`;
  lookbookStore.triggerMode = 'nl';
  lookbookStore.nlQuery = query;
  emit('close');
  router.push('/lookbook');
  await lookbookStore.generateRecommendation({
    triggerType: 'nl_query',
    userQuery: query,
  });
  toast.show(`已按「${props.look.styleTag}」灵感为你生成个人衣橱复刻方案`);
}

function isRealImg(url?: string, key?: string) {
  if (!url || url.startsWith('svg://')) return false;
  if (key && failedPieceImgs.value[key]) return false;
  return true;
}

function markPieceFailed(key: string) {
  failedPieceImgs.value = { ...failedPieceImgs.value, [key]: true };
}
</script>

<template>
  <transition name="modal-fade">
    <div
      v-if="look"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/35 backdrop-blur-[3px] p-0 sm:p-5"
      @click.self="emit('close')"
    >
      <div
        class="modal-sheet relative w-full h-full sm:h-[88vh] sm:max-w-[900px] bg-white sm:rounded-[32px] shadow-float overflow-hidden flex flex-col"
      >
        <!-- Top Floating Controls (Matches Alta Image 2: Top-left "<", Top-right "...") -->
        <button
          @click="emit('close')"
          title="返回灵感广场"
          class="absolute top-4 left-4 z-30 w-10 h-10 rounded-full bg-white/95 hover:bg-[#F5F5F5] border border-black/[0.08] shadow-card flex items-center justify-center text-black transition active:scale-95"
        >
          <AppIcon name="chevron-left" :size="20" />
        </button>

        <div class="absolute top-4 right-4 z-30">
          <button
            @click="menuOpen = !menuOpen"
            title="更多操作"
            class="w-10 h-10 rounded-full bg-white/95 hover:bg-[#F5F5F5] border border-black/[0.08] shadow-card flex items-center justify-center text-black transition active:scale-95"
          >
            <AppIcon name="more-horizontal" :size="18" />
          </button>
          <div
            v-if="menuOpen"
            class="absolute right-0 mt-2 w-48 bg-white rounded-2xl border border-black/10 shadow-float py-1.5 text-[13px]"
          >
            <button
              @click="restyleWithMyCloset"
              class="w-full px-4 py-2 text-left hover:bg-[#F5F5F5] flex items-center gap-2 text-black"
            >
              <AppIcon name="sparkles" :size="14" />
              用我的衣橱复刻
            </button>
            <button
              @click="
                menuOpen = false;
                emit('toggle-like', look.id);
              "
              class="w-full px-4 py-2 text-left hover:bg-[#F5F5F5] flex items-center gap-2 text-black"
            >
              <AppIcon name="heart" :size="14" :fill="look.liked ? '#000000' : 'none'" />
              {{ look.liked ? '取消收藏该造型' : '收藏该造型' }}
            </button>
          </div>
        </div>

        <!-- Stage Area: Left Floating Piece Rail + Center Full-Body Model + Right Bottom Actions -->
        <div class="relative flex-1 bg-white flex items-center justify-center overflow-hidden">
          <!-- Left Vertical Floating Pieces Rail (Matches Alta Image 2 Left Column) -->
          <div
            class="absolute left-3 sm:left-5 top-16 bottom-6 z-20 flex flex-col gap-2.5 overflow-y-auto no-scrollbar py-1 pr-1"
          >
            <button
              v-for="p in look.pieces"
              :key="p.id"
              @click="emit('select-piece', p)"
              class="group flex flex-col items-center w-[62px] sm:w-[74px] shrink-0 focus:outline-none"
              :title="`${p.brand} · ${p.name}（点击查看淘宝/京东同款）`"
            >
              <div
                class="w-[58px] h-[58px] sm:w-[68px] sm:h-[68px] rounded-2xl bg-white border border-black/[0.08] group-hover:border-black shadow-card group-hover:shadow-editorial p-1.5 flex items-center justify-center overflow-hidden transition-all duration-200 group-hover:scale-105"
              >
                <img
                  v-if="isRealImg(p.imageUrl, p.id)"
                  :src="p.imageUrl"
                  :alt="p.name"
                  @error="markPieceFailed(p.id)"
                  class="w-full h-full object-contain"
                />
                <GarmentVisual
                  v-else
                  :image-url="p.imageUrl"
                  :svg-type="p.svgType"
                  :color-hex="p.colorHex"
                  :alt="p.name"
                  class="w-full h-full"
                />
              </div>
              <span
                class="mt-1 w-full text-center text-[10.5px] font-medium text-black truncate px-0.5 group-hover:underline"
              >
                {{ p.brand }}
              </span>
            </button>
          </div>

          <!-- Center Full-Body Model Editorial Photo -->
          <div class="w-full h-full flex flex-col items-center justify-center px-20 sm:px-28 pt-10 pb-16">
            <div class="relative w-full h-full flex items-center justify-center">
              <img
                v-if="!modelImgFailed"
                :src="look.modelImageUrl"
                :alt="look.title"
                @error="modelImgFailed = true"
                class="max-h-full max-w-full object-contain rounded-2xl select-none"
              />
              <div
                v-else
                class="w-60 h-80 rounded-3xl bg-[#F5F5F5] flex flex-col items-center justify-center p-8 gap-3"
              >
                <GarmentVisual :svg-type="look.fallbackSvgType" color-hex="#E5E5E5" class="w-40 h-40" />
                <span class="text-[12px] text-[#666666]">{{ look.title }}</span>
              </div>
            </div>
          </div>

          <!-- Bottom Center Minimal Creator Caption -->
          <div
            class="absolute bottom-4 left-20 right-20 z-20 flex flex-col items-center text-center pointer-events-none"
          >
            <div class="inline-flex items-center gap-2 px-3.5 py-1.5 rounded-full bg-white/90 backdrop-blur-md border border-black/[0.08] shadow-sm pointer-events-auto">
              <span class="text-[12.5px] font-semibold text-black">{{ look.creatorName }}</span>
              <span class="text-black/20">·</span>
              <span class="inline-flex items-center gap-1 text-[11.5px] text-[#666666]">
                <AppIcon name="map-pin" :size="11" />
                {{ look.city }}
              </span>
              <span class="hidden sm:inline text-black/20">·</span>
              <span class="hidden sm:inline text-[11.5px] text-black/75">{{ look.title }}</span>
            </div>
            <p class="text-[11px] text-[#666666] mt-1.5 bg-white/80 px-2.5 py-0.5 rounded-full">
              点击左侧任意单品/饰品，查看淘宝 &amp; 京东相似同款
            </p>
          </div>

          <!-- Bottom-Right Vertical Social Actions (Matches Alta Image 2: Heart / Comment / Restyle) -->
          <div class="absolute right-4 sm:right-6 bottom-6 z-20 flex flex-col items-center gap-4">
            <button
              @click="emit('toggle-like', look.id)"
              class="flex flex-col items-center gap-1 group focus:outline-none"
              title="喜欢 / 收藏"
            >
              <span
                class="w-10 h-10 rounded-full bg-white/95 hover:bg-[#F5F5F5] border border-black/[0.08] shadow-card flex items-center justify-center transition group-active:scale-90"
              >
                <AppIcon
                  name="heart"
                  :size="19"
                  :fill="look.liked ? '#000000' : 'none'"
                  class="text-black"
                />
              </span>
              <span class="text-[11.5px] font-mono text-black">{{ look.likesCount }}</span>
            </button>

            <button
              @click="commentsOpen = !commentsOpen"
              class="flex flex-col items-center gap-1 group focus:outline-none"
              title="查看或发表评论"
            >
              <span
                class="w-10 h-10 rounded-full bg-white/95 hover:bg-[#F5F5F5] border border-black/[0.08] shadow-card flex items-center justify-center transition group-active:scale-90"
              >
                <AppIcon name="message" :size="18" class="text-black" />
              </span>
              <span class="text-[11.5px] font-mono text-black">{{ localComments.length }}</span>
            </button>

            <button
              @click="restyleWithMyCloset"
              class="flex flex-col items-center gap-1 group focus:outline-none"
              title="用我的衣橱复刻此造型"
            >
              <span
                class="w-10 h-10 rounded-full bg-black text-white hover:bg-[#262626] shadow-card flex items-center justify-center transition group-active:scale-90"
              >
                <AppIcon name="refresh" :size="17" />
              </span>
              <span class="text-[11.5px] font-mono text-black">{{ look.restylesCount }}</span>
            </button>
          </div>
        </div>

        <!-- Collapsible Bottom Comments Drawer -->
        <div
          v-if="commentsOpen"
          class="border-t border-black/[0.08] bg-[#FAFAFA] px-5 py-4 space-y-3 shrink-0"
        >
          <div class="flex items-center justify-between">
            <span class="text-[12.5px] font-semibold text-black">造型讨论（{{ localComments.length }}）</span>
            <button @click="commentsOpen = false" class="text-[12px] text-[#666666] hover:text-black">收起</button>
          </div>
          <div class="max-h-28 overflow-y-auto space-y-1.5">
            <div
              v-for="(c, i) in localComments"
              :key="i"
              class="text-[12.5px] text-[#333333] bg-white px-3 py-1.5 rounded-xl border border-black/[0.06]"
            >
              {{ c }}
            </div>
          </div>
          <div class="flex gap-2">
            <input
              v-model="commentInput"
              @keyup.enter="submitComment"
              type="text"
              placeholder="说点穿搭心得或求同款..."
              class="flex-1 px-3.5 py-2 rounded-full bg-white border border-black/10 focus:border-black outline-none text-[12.5px]"
            />
            <button
              @click="submitComment"
              class="px-4 py-2 rounded-full bg-black text-white text-[12px] font-medium hover:bg-[#262626] transition"
            >
              发送
            </button>
          </div>
        </div>
      </div>
    </div>
  </transition>
</template>
