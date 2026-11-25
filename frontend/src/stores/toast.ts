import { defineStore } from 'pinia';
import { ref } from 'vue';

export const useToastStore = defineStore('toast', () => {
  const visible = ref(false);
  const message = ref('');
  const actionLabel = ref('');
  const actionFn = ref<(() => void) | null>(null);
  let timer: ReturnType<typeof setTimeout> | null = null;

  function show(msg: string, label = '', action: (() => void) | null = null) {
    message.value = msg;
    actionLabel.value = label;
    actionFn.value = action;
    visible.value = true;
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => {
      visible.value = false;
    }, 3600);
  }

  function runAction() {
    if (actionFn.value) actionFn.value();
    visible.value = false;
  }

  return { visible, message, actionLabel, show, runAction };
});
