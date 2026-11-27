<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { wardrobeApi, visionApi } from '@/api/modules';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useLookbookStore } from '@/stores/lookbook';
import { useToastStore } from '@/stores/toast';
import { CATEGORY_OPTIONS, COLOR_SWATCHES } from '@/utils/constants';
import type { HistoryRecord } from '@/types';
import AppIcon from './AppIcon.vue';
import GarmentVisual from './GarmentVisual.vue';
import CutoutMaskEditorModal from './CutoutMaskEditorModal.vue';

const router = useRouter();
const wardrobeStore = useWardrobeStore();
const lookbookStore = useLookbookStore();
const toast = useToastStore();

const form = reactive({
  name: '',
  category: 'top',
  subCategory: '',
  color: '',
  colorHex: '#F5F5F5',
  svgType: '',
  processedImageUrl: '',
  price: '' as string | number,
  styleTags: [] as string[],
});
const newTag = ref('');
const deleteConfirm = ref(false);
const saving = ref(false);
const deleting = ref(false);
const styling = ref(false);
const relatedHistory = ref<HistoryRecord[]>([]);
const maskEditorOpen = ref(false);
const savingCutout = ref(false);

const canEditMask = computed(() => {
  const url = form.processedImageUrl || '';
  return Boolean(url) && !url.startsWith('svg://');
});

async function handleMaskApply(payload: { file: File; dataUrl: string }) {
  const item = wardrobeStore.drawerItem;
  if (!item || savingCutout.value) return;
  savingCutout.value = true;
  try {
    const fd = new FormData();
    fd.append('file', payload.file);
    fd.append('cutoutFile', payload.file);
    const stored = await visionApi.storeItemImage(fd);
    await wardrobeStore.updateItem(item.id, {
      processedImageUrl: stored.processedImageUrl,
    });
    form.processedImageUrl = stored.processedImageUrl;
    maskEditorOpen.value = false;
    toast.show('手动精修后的透明抠图已更新保存');
  } catch (e: any) {
    toast.show(e?.message || '精修图片保存失败');
  } finally {
    savingCutout.value = false;
  }
}

watch(
  () => wardrobeStore.drawerItem,
  async (item) => {
    deleteConfirm.value = false;
    newTag.value = '';
    if (!item) {
      relatedHistory.value = [];
      return;
    }
    form.name = item.name;
    form.category = item.category || 'top';
    form.subCategory = item.subCategory || '';
    form.color = item.color;
    form.colorHex = item.colorHex;
    form.svgType = item.svgType || 'tshirt';
    form.processedImageUrl = item.processedImageUrl || '';
    form.price = item.price != null ? Number(item.price) : '';
    form.styleTags = [...(item.styleTags || [])];
    try {
      const detail = await wardrobeApi.detail(item.id);
      relatedHistory.value = detail.relatedHistory || [];
    } catch {
      relatedHistory.value = [];
    }
  },
  { immediate: true }
);

const liveCpw = computed(() => {
  const p = Number(form.price);
  if (!p || p <= 0) return null;
  const wears = wardrobeStore.drawerItem?.wearCount || 0;
  return wears > 0 ? p / wears : p;
});

const cpwInsight = computed(() => {
  if (liveCpw.value == null) return '填写购入价格后即可自动追踪单次穿着成本（CPW）';
  const wears = wardrobeStore.drawerItem?.wearCount || 0;
  if (wears === 0) return '尚未穿着过，多穿几次即可迅速摊薄单次成本';
  if (liveCpw.value <= 35) return '超高性价比核心主力，已充分穿回票价';
  if (liveCpw.value <= 80) return '穿着效率良好，继续保持高频复用';
  return '单次成本偏高，建议用「以此单品为核心搭配」唤醒它';
});

function close() {
  wardrobeStore.drawerItem = null;
  deleteConfirm.value = false;
}

function addTag() {
  const v = newTag.value.trim();
  if (v && !form.styleTags.includes(v)) {
    form.styleTags.push(v);
  }
  newTag.value = '';
}

async function saveChanges() {
  const item = wardrobeStore.drawerItem;
  if (!item) return;
  saving.value = true;
  try {
    const parsedPrice = form.price !== '' && Number(form.price) >= 0 ? Number(form.price) : undefined;
    await wardrobeStore.updateItem(item.id, {
      name: form.name,
      category: form.category,
      subCategory: form.subCategory.trim() || undefined,
      color: form.color,
      colorHex: form.colorHex,
      price: parsedPrice,
      styleTags: [...form.styleTags],
    });
    toast.show('单品属性与 CPW 价格已保存更新');
  } catch (e: any) {
    toast.show(e.message || '保存失败');
  } finally {
    saving.value = false;
  }
}

async function confirmDelete() {
  const item = wardrobeStore.drawerItem;
  if (!item || deleting.value) return;
  const name = item.name;
  deleting.value = true;
  try {
    await wardrobeStore.deleteItem(item.id);
    toast.show(`已删除「${name}」`);
  } catch (e: any) {
    toast.show(e.message || '删除失败');
  } finally {
    deleting.value = false;
  }
}

async function styleWithAnchor() {
  const item = wardrobeStore.drawerItem;
  if (!item || styling.value) return;
  styling.value = true;
  lookbookStore.triggerMode = 'anchor';
  lookbookStore.anchorItemId = item.id;
  toast.show(`正在围绕「${item.name}」生成专属搭配…`);
  try {
    await lookbookStore.generateRecommendation({ triggerType: 'anchor_item', anchorItemId: item.id });
    close();
    router.push('/lookbook');
    toast.show(`已围绕「${item.name}」生成专属搭配方案`);
  } catch (e: any) {
    toast.show(e?.message || '生成搭配失败');
  } finally {
    styling.value = false;
  }
}

function jumpToCalendar(date: string) {
  lookbookStore.selectedCalendarDate = date;
  close();
  router.push('/calendar');
}
</script>

<template>
  <transition name="drawer">
    <div
      v-if="wardrobeStore.drawerItem"
      class="fixed inset-0 z-50 flex justify-end bg-black/30 backdrop-blur-[2px]"
      @click.self="close"
    >
      <div class="drawer-panel w-full max-w-md bg-white h-full shadow-float flex flex-col overflow-hidden">
        <div class="px-6 py-4 border-b border-black/[0.08] flex items-center justify-between">
          <span class="text-[16px] font-semibold tracking-tight text-black">单品详情与 CPW 追踪</span>
          <button
            @click="close"
            class="w-8 h-8 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] flex items-center justify-center text-black transition"
          >
            <AppIcon name="x" :size="15" />
          </button>
        </div>

        <div class="flex-1 overflow-y-auto p-6 space-y-5">
          <!-- Garment Preview Well -->
          <div class="aspect-[4/3] rounded-2xl bg-[#F5F5F5] p-6 flex items-center justify-center relative">
            <GarmentVisual
              :image-url="form.processedImageUrl"
              :svg-type="form.svgType"
              :color-hex="form.colorHex"
              :alt="form.name"
            />
            <button
              v-if="canEditMask"
              type="button"
              @click="maskEditorOpen = true"
              class="absolute top-3 right-3 px-3 py-1 rounded-full bg-white/95 hover:bg-black hover:text-white border border-black/15 text-[11.5px] font-medium text-black shadow-sm transition flex items-center gap-1"
            >
              <AppIcon name="wand" :size="12" />
              精修抠图
            </button>
            <span class="absolute bottom-3 right-3 px-2.5 py-1 rounded-full bg-white/95 border border-black/[0.08] font-mono text-[11px] text-black">
              累计穿着 {{ wardrobeStore.drawerItem.wearCount }} 次
            </span>
          </div>

          <!-- Indyx-Style CPW (Cost-Per-Wear) Analytics Card -->
          <div class="rounded-2xl bg-[#FAFAFA] border border-black/[0.08] p-4 space-y-2.5">
            <div class="flex items-center justify-between">
              <span class="text-[11px] font-mono uppercase tracking-wider text-[#666666]">COST PER WEAR · 单次穿着成本</span>
              <span
                v-if="liveCpw != null"
                class="px-2 py-0.5 rounded-full text-[10.5px] font-mono font-semibold"
                :class="liveCpw <= 40 ? 'bg-black text-white' : 'bg-[#EBEBEB] text-black'"
              >
                ¥{{ liveCpw.toFixed(1) }} / 次
              </span>
            </div>
            <div class="grid grid-cols-3 gap-2 text-center">
              <div class="rounded-xl bg-white border border-black/[0.06] py-2 px-1">
                <div class="font-mono text-[15px] font-semibold text-black">
                  {{ form.price ? `¥${Number(form.price).toFixed(0)}` : '--' }}
                </div>
                <div class="text-[10.5px] text-[#666666] mt-0.5">购入价格</div>
              </div>
              <div class="rounded-xl bg-white border border-black/[0.06] py-2 px-1">
                <div class="font-mono text-[15px] font-semibold text-black">
                  {{ wardrobeStore.drawerItem.wearCount }} 次
                </div>
                <div class="text-[10.5px] text-[#666666] mt-0.5">穿着频次</div>
              </div>
              <div class="rounded-xl bg-white border border-black/[0.06] py-2 px-1">
                <div class="font-mono text-[15px] font-semibold text-black">
                  {{ liveCpw != null ? `¥${liveCpw.toFixed(1)}` : '--' }}
                </div>
                <div class="text-[10.5px] text-[#666666] mt-0.5">单次成本</div>
              </div>
            </div>
            <p class="text-[11.5px] text-[#666666] leading-relaxed">{{ cpwInsight }}</p>
          </div>

          <button
            @click="styleWithAnchor"
            :disabled="styling"
            class="w-full py-3 rounded-full bg-black hover:bg-[#262626] text-white text-[13.5px] font-medium shadow-sm transition flex items-center justify-center gap-2 disabled:opacity-60 disabled:cursor-not-allowed"
          >
            <AppIcon v-if="styling" name="loader" :size="15" class="animate-spin" />
            <AppIcon v-else name="sparkles" :size="15" />
            {{ styling ? '正在生成搭配…' : '以此单品为核心搭配' }}
          </button>

          <div class="space-y-3.5 pt-2 border-t border-black/[0.08]">
            <div class="grid grid-cols-12 gap-3">
              <div class="col-span-7">
                <label class="block text-[12px] text-[#666666] mb-1">单品名称</label>
                <input
                  v-model="form.name"
                  type="text"
                  class="w-full px-3.5 py-2 rounded-xl bg-[#F5F5F5] border border-transparent focus:border-black focus:bg-white text-[13.5px] font-medium outline-none transition"
                />
              </div>
              <div class="col-span-5">
                <label class="block text-[12px] text-[#666666] mb-1">购入价 (¥)</label>
                <input
                  v-model="form.price"
                  type="number"
                  min="0"
                  step="1"
                  placeholder="如 299"
                  class="w-full px-3 py-2 rounded-xl bg-[#F5F5F5] border border-transparent focus:border-black focus:bg-white font-mono text-[13.5px] font-medium outline-none transition"
                />
              </div>
            </div>

            <div class="space-y-2">
              <label class="block text-[12px] text-[#666666]">衣橱分类</label>
              <div class="flex flex-wrap gap-1.5">
                <button
                  v-for="cat in CATEGORY_OPTIONS"
                  :key="cat.key"
                  type="button"
                  @click="form.category = cat.key"
                  class="px-3 py-1 rounded-full text-[12px] font-medium transition"
                  :class="form.category === cat.key ? 'bg-black text-white' : 'bg-[#F5F5F5] text-[#333333] hover:bg-[#EBEBEB]'"
                >
                  {{ cat.label }}
                </button>
              </div>
              <input
                v-model="form.subCategory"
                type="text"
                placeholder="细分子类（如：链条包、过膝丝袜、吊带连衣裙）"
                class="w-full px-3.5 py-2 rounded-xl bg-[#F5F5F5] border border-transparent focus:border-black focus:bg-white text-[12.5px] outline-none transition"
              />
            </div>

            <div>
              <label class="block text-[12px] text-[#666666] mb-1.5">主色调（{{ form.color }}）</label>
              <div class="flex flex-wrap gap-2">
                <button
                  v-for="sw in COLOR_SWATCHES"
                  :key="sw.name"
                  type="button"
                  @click="
                    form.color = sw.name;
                    form.colorHex = sw.hex;
                  "
                  class="w-6 h-6 rounded-full border-2 transition"
                  :style="{ backgroundColor: sw.hex }"
                  :class="form.color === sw.name ? 'border-black scale-110 ring-2 ring-black/20' : 'border-white shadow-sm'"
                  :title="sw.name"
                />
              </div>
            </div>

            <div>
              <label class="block text-[12px] text-[#666666] mb-1.5">风格标签</label>
              <div class="flex flex-wrap gap-1.5 mb-2">
                <span
                  v-for="(tag, idx) in form.styleTags"
                  :key="tag"
                  class="inline-flex items-center gap-1 px-2.5 py-1 rounded-full bg-[#F5F5F5] text-[12px] text-black"
                >
                  {{ tag }}
                  <button type="button" @click="form.styleTags.splice(idx, 1)" class="text-[#666666] hover:text-black">×</button>
                </span>
              </div>
              <div class="flex gap-2">
                <input
                  v-model="newTag"
                  @keyup.enter="addTag"
                  type="text"
                  placeholder="添加新标签"
                  class="flex-1 px-3 py-1.5 rounded-full bg-[#F5F5F5] border border-transparent focus:border-black focus:bg-white text-[12.5px] outline-none"
                />
                <button type="button" @click="addTag" class="px-3.5 py-1.5 rounded-full bg-[#EBEBEB] hover:bg-black hover:text-white text-[12px] font-medium transition">
                  添加
                </button>
              </div>
            </div>

            <button
              @click="saveChanges"
              :disabled="saving"
              class="w-full py-2.5 rounded-full bg-black hover:bg-[#262626] text-white text-[13px] font-medium transition inline-flex items-center justify-center gap-1.5 disabled:opacity-60 disabled:cursor-not-allowed"
            >
              <AppIcon v-if="saving" name="loader" :size="13" class="animate-spin" />
              {{ saving ? '保存中...' : '保存属性与价格' }}
            </button>
          </div>

          <div class="pt-4 border-t border-black/[0.08]">
            <h4 class="text-[13px] font-semibold text-black mb-2.5">反查：该单品参与过的穿搭方案</h4>
            <div v-if="relatedHistory.length > 0" class="space-y-2">
              <div
                v-for="h in relatedHistory"
                :key="h.id"
                @click="jumpToCalendar(h.wearDate)"
                class="p-3 rounded-2xl bg-[#FAFAFA] hover:bg-[#F2F2F2] border border-black/[0.06] cursor-pointer flex items-center justify-between transition"
              >
                <div>
                  <div class="text-[13px] font-medium text-black">{{ h.themeTitle }}</div>
                  <div class="text-[11px] text-[#666666] font-mono mt-0.5">{{ h.wearDate }} · {{ h.sceneTag }}</div>
                </div>
                <span class="text-[12px] text-black font-medium">查看 →</span>
              </div>
            </div>
            <p v-else class="text-[12px] text-[#666666]">暂未在穿搭日历中记录过该单品</p>
          </div>

          <div class="pt-4 border-t border-black/[0.08]">
            <div v-if="!deleteConfirm">
              <button
                @click="deleteConfirm = true"
                class="w-full py-2.5 rounded-full border border-red-200 text-red-600 hover:bg-red-50 text-[12.5px] font-medium transition"
              >
                从衣橱移除此单品
              </button>
            </div>
            <div v-else class="p-3.5 rounded-2xl bg-red-50 border border-red-200 space-y-2.5">
              <p class="text-[12px] text-red-700 font-medium">确认永久移除「{{ wardrobeStore.drawerItem.name }}」？</p>
              <div class="flex gap-2">
                <button
                  @click="confirmDelete"
                  :disabled="deleting"
                  class="flex-1 py-2 rounded-full bg-red-600 hover:bg-red-700 text-white text-[12px] font-medium transition inline-flex items-center justify-center gap-1.5 disabled:opacity-60"
                >
                  <AppIcon v-if="deleting" name="loader" :size="12" class="animate-spin" />
                  {{ deleting ? '移除中…' : '确认移除' }}
                </button>
                <button
                  @click="deleteConfirm = false"
                  class="flex-1 py-2 rounded-full bg-white border border-black/10 text-black text-[12px] font-medium"
                >
                  取消
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </transition>

  <CutoutMaskEditorModal
    :open="maskEditorOpen"
    :title="form.name"
    :original-url="wardrobeStore.drawerItem?.originalImageUrl || form.processedImageUrl"
    :cutout-url="form.processedImageUrl"
    @close="maskEditorOpen = false"
    @apply="handleMaskApply"
  />
</template>
