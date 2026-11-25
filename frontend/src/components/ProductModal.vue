<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useToastStore } from '@/stores/toast';
import { platformBadgeClass } from '@/utils/constants';
import AppIcon from './AppIcon.vue';
import GarmentVisual from './GarmentVisual.vue';

const router = useRouter();
const wardrobeStore = useWardrobeStore();
const toast = useToastStore();
const adding = ref(false);

const taobaoSearchUrl = computed(() => {
  const title = wardrobeStore.activeProductModal?.title || '';
  return `https://s.taobao.com/search?q=${encodeURIComponent(title)}`;
});

const jdSearchUrl = computed(() => {
  const title = wardrobeStore.activeProductModal?.title || '';
  return `https://search.jd.com/Search?keyword=${encodeURIComponent(title)}`;
});

async function mockPurchaseAndAdd() {
  const prod = wardrobeStore.activeProductModal;
  if (!prod) return;
  adding.value = true;
  try {
    await wardrobeStore.createItem({
      name: prod.title.slice(0, 14),
      category: prod.category,
      color: prod.color || '燕麦',
      colorHex: prod.colorHex || '#DFD3C3',
      seasons: ['春', '秋'],
      styleTags: prod.styleTags?.length ? prod.styleTags : ['导购新入', '极简通勤'],
      svgType: prod.svgType || 'trench',
      processedImageUrl: prod.imageUrl || `svg://${prod.svgType || 'trench'}`,
      sourceType: 'screenshot',
      price: Number(prod.price) || undefined,
    });
    wardrobeStore.activeProductModal = null;
    toast.show(`已将「${prod.title.slice(0, 10)}」购入并计入 CPW 衣橱资产`, '去衣橱', () => {
      router.push('/wardrobe');
    });
  } catch (e: any) {
    toast.show(e.message || '加入衣橱失败');
  } finally {
    adding.value = false;
  }
}
</script>

<template>
  <transition name="modal-fade">
    <div
      v-if="wardrobeStore.activeProductModal"
      class="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40 backdrop-blur-[2px]"
      @click.self="wardrobeStore.activeProductModal = null"
    >
      <div class="modal-sheet bg-white rounded-3xl border border-black/10 shadow-float max-w-md w-full p-6 space-y-4">
        <div class="flex items-center justify-between">
          <span
            class="text-[11px] font-medium px-2.5 py-1 rounded-full"
            :class="platformBadgeClass(wardrobeStore.activeProductModal.platform)"
          >
            {{ wardrobeStore.activeProductModal.platformLabel }} · 精选同款
          </span>
          <button
            @click="wardrobeStore.activeProductModal = null"
            class="w-8 h-8 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] flex items-center justify-center text-black"
          >
            <AppIcon name="x" :size="15" />
          </button>
        </div>

        <div class="aspect-[16/10] rounded-2xl bg-[#F5F5F5] p-5 flex items-center justify-center">
          <GarmentVisual
            :image-url="wardrobeStore.activeProductModal.imageUrl"
            :svg-type="wardrobeStore.activeProductModal.svgType"
            :color-hex="wardrobeStore.activeProductModal.colorHex"
            :alt="wardrobeStore.activeProductModal.title"
          />
        </div>

        <div>
          <h4 class="text-[17px] font-semibold text-black leading-snug">
            {{ wardrobeStore.activeProductModal.title }}
          </h4>
          <p class="text-[12.5px] text-[#666666] mt-1 leading-relaxed">
            {{ wardrobeStore.activeProductModal.description }}
          </p>
          <div class="flex items-baseline justify-between mt-3 gap-2">
            <span class="font-mono text-2xl font-semibold text-black">
              ¥{{ wardrobeStore.activeProductModal.price }}
            </span>
            <span class="text-[11px] text-[#666666]">支持淘宝 / 京东直达比价</span>
          </div>
        </div>

        <!-- Direct China E-commerce Links -->
        <div class="grid grid-cols-2 gap-2.5">
          <a
            :href="taobaoSearchUrl"
            target="_blank"
            rel="noopener noreferrer"
            class="py-2.5 rounded-full bg-[#FF5000] hover:bg-[#e64800] text-white text-[12.5px] font-medium flex items-center justify-center gap-1.5 transition"
          >
            去淘宝搜同款
            <AppIcon name="external-link" :size="13" />
          </a>
          <a
            :href="jdSearchUrl"
            target="_blank"
            rel="noopener noreferrer"
            class="py-2.5 rounded-full bg-[#E1251B] hover:bg-[#c81f16] text-white text-[12.5px] font-medium flex items-center justify-center gap-1.5 transition"
          >
            去京东搜同款
            <AppIcon name="external-link" :size="13" />
          </a>
        </div>

        <div class="flex gap-2.5 pt-1">
          <button
            @click="mockPurchaseAndAdd"
            :disabled="adding"
            class="flex-1 py-2.5 rounded-full bg-black hover:bg-[#262626] text-white text-[13px] font-medium transition disabled:opacity-60"
          >
            {{ adding ? '正在加入衣橱...' : '已购入并加入衣橱（计入 CPW）' }}
          </button>
        </div>
      </div>
    </div>
  </transition>
</template>
