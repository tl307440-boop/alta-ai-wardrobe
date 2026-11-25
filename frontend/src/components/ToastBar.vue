<script setup lang="ts">
import { useToastStore } from '@/stores/toast';
import AppIcon from './AppIcon.vue';

const toast = useToastStore();
</script>

<template>
  <transition name="toast">
    <div
      v-if="toast.visible"
      class="fixed bottom-24 left-1/2 -translate-x-1/2 z-[60] px-5 py-3 rounded-full bg-black text-white shadow-float flex items-center gap-3 text-[13px] max-w-[92vw]"
    >
      <AppIcon name="check" :size="15" class="text-white shrink-0" />
      <span class="truncate">{{ toast.message }}</span>
      <button
        v-if="toast.actionLabel"
        @click="toast.runAction"
        class="ml-1 px-3 py-0.5 rounded-full bg-white text-black text-[11.5px] font-semibold shrink-0 hover:bg-white/90 transition"
      >
        {{ toast.actionLabel }}
      </button>
    </div>
  </transition>
</template>
