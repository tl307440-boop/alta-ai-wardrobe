<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import GarmentSvg from './GarmentSvg.vue';

const props = defineProps<{
  imageUrl?: string | null;
  svgType?: string | null;
  colorHex?: string | null;
  alt?: string;
}>();

const imgFailed = ref(false);

watch(
  () => props.imageUrl,
  () => {
    imgFailed.value = false;
  }
);

const useRealImage = computed(() => {
  if (imgFailed.value || !props.imageUrl) return false;
  const u = props.imageUrl.trim();
  if (u.startsWith('svg://')) return false;
  return (
    u.startsWith('/alta/') ||
    u.startsWith('/uploads/') ||
    u.startsWith('data:image/') ||
    u.startsWith('http://') ||
    u.startsWith('https://')
  );
});
</script>

<template>
  <img
    v-if="useRealImage"
    :src="imageUrl!"
    :alt="alt || '衣物图片'"
    class="w-full h-full object-contain rounded-xl drop-shadow-sm select-none"
    @error="imgFailed = true"
  />
  <GarmentSvg
    v-else
    :type="svgType || 'tshirt'"
    :color="colorHex || '#EFECE6'"
    class="w-full h-full"
  />
</template>
