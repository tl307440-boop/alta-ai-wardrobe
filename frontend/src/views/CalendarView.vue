<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { outfitApi, visionApi } from '@/api/modules';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useLookbookStore } from '@/stores/lookbook';
import { useToastStore } from '@/stores/toast';
import type { OutfitCritiqueResult, OutfitPlanVo } from '@/types';
import AppIcon from '@/components/AppIcon.vue';
import GarmentVisual from '@/components/GarmentVisual.vue';

const route = useRoute();
const router = useRouter();
const wardrobeStore = useWardrobeStore();
const lookbookStore = useLookbookStore();
const toast = useToastStore();

const subTab = ref<'calendar' | 'favorites'>(route.query.tab === 'favorites' ? 'favorites' : 'calendar');
const calendarMode = ref<'photo' | 'palette'>('photo');

const now = new Date();
const viewYear = ref(now.getFullYear());
const viewMonth = ref(now.getMonth() + 1);

const todayStr = computed(() => {
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
});

const selectedDate = computed(() => lookbookStore.selectedCalendarDate || todayStr.value);

const calendarCells = computed(() => {
  const firstDow = new Date(viewYear.value, viewMonth.value - 1, 1).getDay();
  const daysInMonth = new Date(viewYear.value, viewMonth.value, 0).getDate();
  const cells: Array<{
    blank?: boolean;
    dayNum?: number;
    dateStr?: string;
    record?: (typeof lookbookStore.history)[number] | null;
  }> = [];
  for (let i = 0; i < firstDow; i++) cells.push({ blank: true });
  for (let d = 1; d <= daysInMonth; d++) {
    const ds = `${viewYear.value}-${String(viewMonth.value).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
    const rec = lookbookStore.history.find((h) => h.wearDate === ds) || null;
    cells.push({ dayNum: d, dateStr: ds, record: rec });
  }
  return cells;
});

const monthRecordCount = computed(() => {
  const prefix = `${viewYear.value}-${String(viewMonth.value).padStart(2, '0')}`;
  return lookbookStore.history.filter((h) => h.wearDate.startsWith(prefix)).length;
});

const monthPhotoCount = computed(() => {
  const prefix = `${viewYear.value}-${String(viewMonth.value).padStart(2, '0')}`;
  return lookbookStore.history.filter((h) => h.wearDate.startsWith(prefix) && !!h.ootdPhotoUrl).length;
});

const selectedRecord = computed(
  () => lookbookStore.history.find((h) => h.wearDate === selectedDate.value) || null
);

const noteDraft = ref('');
const photoReplaceInputRef = ref<HTMLInputElement | null>(null);
const uploadingRecordPhoto = ref(false);
const savingNotes = ref(false);
const savingRating = ref(false);
const deletingPhoto = ref(false);
const removingFavId = ref<number | null>(null);

// AI Outfit Critique state for existing OOTD photo
const critiquingOotd = ref(false);
const ootdCritique = ref<{ historyId: number; result: OutfitCritiqueResult } | null>(null);

// OOTD real-photo check-in form for dates without a record yet
const checkinFileInputRef = ref<HTMLInputElement | null>(null);
const checkin = reactive({
  file: null as File | null,
  previewUrl: '' as string,
  themeTitle: '',
  sceneTag: '日常实拍',
  notes: '',
  feedbackScore: 5,
  selectedItemIds: [] as number[],
  detecting: false,
  submitting: false,
});

const CHECKIN_SCENES = ['日常实拍', '职场通勤', '周末约会', '咖啡馆办公', '旅行出街'];

function resetCheckinDraft() {
  if (checkin.previewUrl) {
    URL.revokeObjectURL(checkin.previewUrl);
  }
  checkin.file = null;
  checkin.previewUrl = '';
  checkin.themeTitle = '';
  checkin.sceneTag = '日常实拍';
  checkin.notes = '';
  checkin.feedbackScore = 5;
  checkin.selectedItemIds = [];
  checkin.detecting = false;
  checkin.submitting = false;
}

onBeforeUnmount(() => {
  if (checkin.previewUrl) URL.revokeObjectURL(checkin.previewUrl);
});

watch(
  selectedRecord,
  (rec) => {
    noteDraft.value = rec?.notes || '';
    if (!rec || (ootdCritique.value && ootdCritique.value.historyId !== rec.id)) {
      ootdCritique.value = null;
    }
  },
  { immediate: true }
);

watch(selectedDate, () => {
  resetCheckinDraft();
});

function selectDate(ds: string) {
  lookbookStore.selectedCalendarDate = ds;
}

function jumpToToday() {
  viewYear.value = now.getFullYear();
  viewMonth.value = now.getMonth() + 1;
  lookbookStore.selectedCalendarDate = todayStr.value;
}

function shiftMonth(delta: number) {
  let m = viewMonth.value + delta;
  let y = viewYear.value;
  if (m < 1) {
    m = 12;
    y -= 1;
  } else if (m > 12) {
    m = 1;
    y += 1;
  }
  viewYear.value = y;
  viewMonth.value = m;
}

async function setRating(star: number) {
  if (!selectedRecord.value || savingRating.value) return;
  savingRating.value = true;
  try {
    await lookbookStore.updateHistoryRecord(selectedRecord.value.id, { feedbackScore: star });
    toast.show(`已更新满意度为 ${star} 星，隐性偏好权重已同步演进`);
  } catch (err: any) {
    toast.show(err?.message || '评分更新失败');
  } finally {
    savingRating.value = false;
  }
}

async function saveNotes() {
  if (!selectedRecord.value || savingNotes.value) return;
  savingNotes.value = true;
  try {
    await lookbookStore.updateHistoryRecord(selectedRecord.value.id, { notes: noteDraft.value });
    toast.show('个人穿搭心得笔记已保存');
  } catch (err: any) {
    toast.show(err?.message || '保存心得失败');
  } finally {
    savingNotes.value = false;
  }
}

async function onReplaceHistoryPhoto(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file || !selectedRecord.value) return;
  uploadingRecordPhoto.value = true;
  try {
    await lookbookStore.uploadHistoryPhoto(selectedRecord.value.id, file);
    ootdCritique.value = null;
    toast.show('当日实拍 OOTD 照片已上传，已同步至实拍日历墙');
  } catch (err: any) {
    toast.show(err.message || '上传照片失败');
  } finally {
    uploadingRecordPhoto.value = false;
  }
}

async function handleDeleteHistoryPhoto() {
  if (!selectedRecord.value || deletingPhoto.value) return;
  deletingPhoto.value = true;
  try {
    await lookbookStore.deleteHistoryPhoto(selectedRecord.value.id);
    ootdCritique.value = null;
    toast.show('已移除当日实拍照片');
  } catch (err: any) {
    toast.show(err.message || '移除失败');
  } finally {
    deletingPhoto.value = false;
  }
}

async function runOotdCritique() {
  const rec = selectedRecord.value;
  if (!rec || !rec.ootdPhotoUrl || critiquingOotd.value) return;
  critiquingOotd.value = true;
  try {
    const resp = await fetch(rec.ootdPhotoUrl);
    if (!resp.ok) throw new Error('无法读取当日实拍照片');
    const blob = await resp.blob();
    const ext = blob.type.includes('png') ? 'png' : 'jpg';
    const file = new File([blob], `ootd-${rec.wearDate}.${ext}`, { type: blob.type || 'image/jpeg' });

    const fd = new FormData();
    fd.append('file', file);
    if (rec.sceneTag) fd.append('sceneTag', rec.sceneTag);
    if (rec.weatherInfo) fd.append('weatherInfo', rec.weatherInfo);
    if (rec.itemIds?.length) fd.append('itemIds', JSON.stringify(rec.itemIds));
    fd.append('source', 'ootd_photo');

    const res = await outfitApi.critique(fd);
    ootdCritique.value = { historyId: rec.id, result: res };
  } catch (err: any) {
    toast.show(err.message || 'AI 实拍体检失败');
  } finally {
    critiquingOotd.value = false;
  }
}

function onSelectCheckinPhoto(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  if (checkin.previewUrl) URL.revokeObjectURL(checkin.previewUrl);
  checkin.file = file;
  checkin.previewUrl = URL.createObjectURL(file);
}

function toggleCheckinItem(id: number) {
  const idx = checkin.selectedItemIds.indexOf(id);
  if (idx >= 0) checkin.selectedItemIds.splice(idx, 1);
  else checkin.selectedItemIds.push(id);
}

async function autoDetectWornItems() {
  if (!checkin.file || checkin.detecting) return;
  checkin.detecting = true;
  try {
    const fd = new FormData();
    fd.append('file', checkin.file);
    fd.append('sourceType', 'photo');
    const res = await visionApi.detectItems(fd);
    if (!res.usedRealLlm || !res.items?.length) {
      toast.show(res.notice || '未配置多模态识别 Key，可直接点击下方衣橱单品快速勾选');
      return;
    }
    const matched = new Set<number>(checkin.selectedItemIds);
    let newlyMatched = 0;
    for (const det of res.items) {
      let found = det.similarWardrobeName
        ? wardrobeStore.items.find((w) => w.name === det.similarWardrobeName)
        : undefined;
      if (!found) {
        found = wardrobeStore.items.find(
          (w) => w.category === det.category && (w.color === det.color || w.name.includes(det.color))
        );
      }
      if (found && !matched.has(found.id)) {
        matched.add(found.id);
        newlyMatched += 1;
      }
    }
    checkin.selectedItemIds = Array.from(matched);
    if (newlyMatched > 0) {
      toast.show(`AI 已从照片自动匹配并勾选 ${newlyMatched} 件衣橱单品`);
    } else {
      toast.show('已识别出图中衣物，但衣橱中暂无高相似已有单品，可手动勾选');
    }
  } catch (err: any) {
    toast.show(err.message || 'AI 识别失败，可手动勾选今日所穿衣物');
  } finally {
    checkin.detecting = false;
  }
}

async function submitOotdCheckin() {
  if (!checkin.file || checkin.submitting) return;
  checkin.submitting = true;
  try {
    const w = wardrobeStore.activeWeather;
    const fd = new FormData();
    fd.append('file', checkin.file);
    fd.append('wearDate', selectedDate.value);
    fd.append('themeTitle', checkin.themeTitle.trim() || `${selectedDate.value.slice(5)} 实拍 OOTD`);
    fd.append('sceneTag', checkin.sceneTag);
    fd.append('weatherInfo', `${w.condition} ${w.temp}℃`);
    fd.append('weatherTemp', String(w.temp));
    fd.append('weatherIcon', w.icon || 'sun');
    if (checkin.selectedItemIds.length) {
      fd.append('itemIds', JSON.stringify(checkin.selectedItemIds));
    }
    if (checkin.notes.trim()) fd.append('notes', checkin.notes.trim());
    fd.append('feedbackScore', String(checkin.feedbackScore));

    await lookbookStore.checkinOotd(fd);
    resetCheckinDraft();
    toast.show('实拍 OOTD 已打卡写入日历，关联衣物穿着次数与 CPW 已同步更新');
  } catch (err: any) {
    toast.show(err.message || '打卡保存失败');
  } finally {
    checkin.submitting = false;
  }
}

async function removeFavorite(id: number) {
  if (removingFavId.value !== null) return;
  removingFavId.value = id;
  try {
    await lookbookStore.toggleFavorite(id);
    toast.show('已取消收藏');
  } catch (err: any) {
    toast.show(err?.message || '操作失败');
  } finally {
    removingFavId.value = null;
  }
}

function openLookInEngine(plan: OutfitPlanVo) {
  lookbookStore.loadPlan(plan);
  router.push('/lookbook');
  toast.show(`已在画板加载「${plan.themeTitle}」`);
}
</script>

<template>
  <section class="space-y-5">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <div class="inline-flex p-1 rounded-full bg-[#F5F5F5]" role="tablist">
        <button
          role="tab"
          :aria-selected="subTab === 'calendar'"
          @click="subTab = 'calendar'"
          class="px-5 py-2 rounded-full text-[13px] font-medium transition"
          :class="subTab === 'calendar' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
        >
          穿搭日历
        </button>
        <button
          role="tab"
          :aria-selected="subTab === 'favorites'"
          @click="subTab = 'favorites'"
          class="px-5 py-2 rounded-full text-[13px] font-medium transition flex items-center gap-1.5"
          :class="subTab === 'favorites' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
        >
          收藏方案
          <span
            class="font-mono text-[11px] px-1.5 rounded-full leading-[18px]"
            :class="subTab === 'favorites' ? 'bg-white/20 text-white' : 'bg-[#E5E5E5] text-black'"
          >
            {{ lookbookStore.favorites.length }}
          </span>
        </button>
      </div>
      <button @click="router.push('/lookbook')" class="text-[12.5px] font-medium text-black hover:text-[#666666] transition">
        + 去画板生成新穿搭
      </button>
    </div>

    <!-- Calendar View -->
    <div v-if="subTab === 'calendar'" class="grid grid-cols-1 lg:grid-cols-12 gap-6">
      <div class="lg:col-span-7 bg-white rounded-3xl border border-black/[0.08] p-6">
        <div class="flex flex-wrap items-center justify-between gap-3 mb-5">
          <div>
            <h3 class="text-xl font-semibold text-black">{{ viewYear }}年{{ viewMonth }}月</h3>
            <p class="text-[12px] text-[#666666] mt-0.5">
              本月已记录 <span class="font-mono text-black font-semibold">{{ monthRecordCount }}</span> 天
              <span v-if="monthPhotoCount > 0">
                · 含 <span class="font-mono text-black font-semibold">{{ monthPhotoCount }}</span> 张实拍 OOTD
              </span>
              <span class="hidden sm:inline"> · 点击任意日期即可打卡</span>
            </p>
          </div>
          <div class="flex flex-wrap items-center gap-2">
            <!-- Photo Wall / Palette Mode Toggle -->
            <div class="inline-flex p-0.5 rounded-full bg-[#F5F5F5] border border-black/[0.06] text-[11px]">
              <button
                type="button"
                @click="calendarMode = 'photo'"
                class="px-2.5 py-1 rounded-full font-medium transition"
                :class="calendarMode === 'photo' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
              >
                实拍 OOTD 墙
              </button>
              <button
                type="button"
                @click="calendarMode = 'palette'"
                class="px-2.5 py-1 rounded-full font-medium transition"
                :class="calendarMode === 'palette' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
              >
                色卡日程
              </button>
            </div>

            <div class="flex items-center gap-1">
              <button
                type="button"
                @click="shiftMonth(-1)"
                class="w-8 h-8 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] text-black flex items-center justify-center transition"
              >
                <AppIcon name="chevron-left" :size="15" />
              </button>
              <button
                type="button"
                @click="jumpToToday"
                class="px-3 py-1 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] border border-black/[0.08] text-[11.5px] text-black font-mono transition"
              >
                今天 {{ todayStr.slice(5).replace('-', '.') }}
              </button>
              <button
                type="button"
                @click="shiftMonth(1)"
                class="w-8 h-8 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] text-black flex items-center justify-center transition"
              >
                <AppIcon name="chevron-right" :size="15" />
              </button>
            </div>
          </div>
        </div>

        <div class="grid grid-cols-7 gap-2 text-center text-[11.5px] font-mono text-[#666666] mb-2">
          <div v-for="wd in ['日', '一', '二', '三', '四', '五', '六']" :key="wd">{{ wd }}</div>
        </div>

        <div class="grid grid-cols-7 gap-2">
          <template v-for="(cell, i) in calendarCells" :key="cell.dateStr || 'b-' + i">
            <div v-if="cell.blank" class="aspect-square" />
            <button
              v-else
              type="button"
              @click="selectDate(cell.dateStr!)"
              class="aspect-square rounded-2xl overflow-hidden flex flex-col justify-between text-left border transition relative group"
              :class="[
                cell.record?.ootdPhotoUrl && calendarMode === 'photo'
                  ? selectedDate === cell.dateStr
                    ? 'border-black ring-2 ring-black ring-offset-1 p-0'
                    : 'border-black/15 hover:border-black p-0'
                  : cell.record
                    ? selectedDate === cell.dateStr
                      ? 'bg-black text-white border-black shadow-md p-1.5 sm:p-2'
                      : 'bg-[#FAFAFA] hover:bg-[#F5F5F5] border-black/[0.08] p-1.5 sm:p-2'
                    : selectedDate === cell.dateStr
                      ? 'bg-[#F5F5F5] border-2 border-dashed border-black text-black p-1.5 sm:p-2'
                      : 'bg-transparent hover:bg-[#FAFAFA] border-transparent hover:border-black/15 text-[#666666]/80 p-1.5 sm:p-2',
                cell.dateStr === todayStr && selectedDate !== cell.dateStr ? 'ring-1 ring-black/30' : '',
              ]"
            >
              <!-- Real OOTD Photo Wall Tile -->
              <template v-if="cell.record?.ootdPhotoUrl && calendarMode === 'photo'">
                <img
                  :src="cell.record.ootdPhotoUrl"
                  :alt="cell.record.themeTitle"
                  class="absolute inset-0 w-full h-full object-cover group-hover:scale-105 transition duration-300"
                />
                <div class="absolute inset-0 bg-gradient-to-b from-black/35 via-transparent to-black/25 pointer-events-none" />
                <span class="relative z-10 m-1.5 px-1.5 py-0.5 rounded-full bg-black/65 backdrop-blur-sm text-white font-mono text-[10.5px] font-semibold w-fit">
                  {{ cell.dayNum }}
                </span>
              </template>

              <!-- Standard Palette / Empty Tile -->
              <template v-else>
                <div class="flex items-center justify-between w-full">
                  <span class="font-mono text-[12px] font-semibold">{{ cell.dayNum }}</span>
                  <span
                    v-if="cell.record"
                    class="hidden sm:inline font-mono text-[10px]"
                    :class="selectedDate === cell.dateStr ? 'text-white/70' : 'text-black'"
                  >
                    {{ cell.record.weatherTemp }}°
                  </span>
                  <AppIcon
                    v-else
                    name="plus"
                    :size="11"
                    class="opacity-0 group-hover:opacity-60 transition"
                  />
                </div>
                <div v-if="cell.record" class="flex items-center justify-between w-full">
                  <div class="flex -space-x-1">
                    <span
                      v-for="(c, idx) in cell.record.palette.slice(0, 3)"
                      :key="idx"
                      class="w-2.5 h-2.5 rounded-full border border-white/80"
                      :style="{ backgroundColor: c }"
                    />
                  </div>
                  <AppIcon
                    v-if="cell.record.ootdPhotoUrl"
                    name="camera"
                    :size="11"
                    class="opacity-80"
                  />
                  <AppIcon
                    v-else
                    :name="cell.record.weatherIcon || 'cloud-sun'"
                    :size="12"
                    class="hidden sm:inline opacity-80"
                  />
                </div>
              </template>
            </button>
          </template>
        </div>
      </div>

      <!-- Daily Archive / OOTD Check-in Panel -->
      <div class="lg:col-span-5 bg-white rounded-3xl border border-black/[0.08] p-6 flex flex-col justify-between">
        <!-- Hidden file input for attaching/replacing OOTD photo on an existing record -->
        <input
          ref="photoReplaceInputRef"
          type="file"
          accept="image/png,image/jpeg,image/webp"
          class="hidden"
          @change="onReplaceHistoryPhoto"
        />

        <!-- State A: Selected Date Has Existing Record -->
        <div v-if="selectedRecord" class="space-y-4">
          <div class="flex items-center justify-between pb-4 border-b border-black/[0.06] gap-2">
            <div>
              <span class="font-mono text-[12px] text-black font-semibold">{{ selectedRecord.wearDate }}</span>
              <h3 class="text-xl font-semibold text-black mt-0.5">{{ selectedRecord.themeTitle }}</h3>
            </div>
            <div class="text-right shrink-0">
              <div class="inline-flex items-center gap-1 px-2.5 py-1 rounded-full bg-[#F5F5F5] text-[12px] text-black">
                <AppIcon :name="selectedRecord.weatherIcon || 'cloud-sun'" :size="13" class="text-black" />
                {{ selectedRecord.weatherInfo }}
              </div>
              <div class="text-[11px] text-[#666666] mt-1">场景：{{ selectedRecord.sceneTag }}</div>
            </div>
          </div>

          <!-- Real OOTD Mirror-Selfie Photo Block -->
          <div v-if="selectedRecord.ootdPhotoUrl" class="space-y-2.5">
            <div class="relative rounded-2xl overflow-hidden bg-[#F5F5F5] border border-black/[0.06]">
              <img
                :src="selectedRecord.ootdPhotoUrl"
                :alt="selectedRecord.themeTitle"
                class="w-full max-h-[340px] object-cover"
              />
              <span class="absolute top-3 left-3 px-2.5 py-1 rounded-full bg-black/70 backdrop-blur text-white font-mono text-[10.5px]">
                REAL OOTD
              </span>
            </div>
            <div class="flex flex-wrap items-center justify-between gap-2">
              <button
                type="button"
                @click="runOotdCritique"
                :disabled="critiquingOotd"
                class="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-full bg-black hover:bg-[#262626] text-white text-[12px] font-medium transition disabled:opacity-55 disabled:cursor-not-allowed"
              >
                <AppIcon v-if="critiquingOotd" name="loader" :size="13" class="animate-spin" />
                <AppIcon v-else name="sparkles" :size="13" />
                {{ critiquingOotd ? 'AI 审图体检中...' : 'AI 体检这套实拍' }}
              </button>
              <div class="flex items-center gap-1.5">
                <button
                  type="button"
                  @click="photoReplaceInputRef?.click()"
                  :disabled="uploadingRecordPhoto"
                  class="inline-flex items-center gap-1 px-3 py-1.5 rounded-full border border-black/15 hover:border-black text-[11.5px] text-black transition disabled:opacity-55 disabled:cursor-not-allowed"
                >
                  <AppIcon v-if="uploadingRecordPhoto" name="loader" :size="12" class="animate-spin" />
                  {{ uploadingRecordPhoto ? '上传中...' : '更换实拍' }}
                </button>
                <button
                  type="button"
                  @click="handleDeleteHistoryPhoto"
                  :disabled="deletingPhoto"
                  title="移除实拍照片"
                  class="w-7 h-7 rounded-full border border-black/10 hover:border-black text-[#666666] hover:text-black flex items-center justify-center transition disabled:opacity-50"
                >
                  <AppIcon v-if="deletingPhoto" name="loader" :size="13" class="animate-spin" />
                  <AppIcon v-else name="trash" :size="13" />
                </button>
              </div>
            </div>
          </div>

          <button
            v-else
            type="button"
            @click="photoReplaceInputRef?.click()"
            :disabled="uploadingRecordPhoto"
            class="w-full py-3 px-4 rounded-2xl border border-dashed border-black/20 hover:border-black bg-[#FAFAFA] hover:bg-[#F5F5F5] text-[12.5px] text-black flex items-center justify-center gap-2 transition disabled:opacity-60 disabled:cursor-not-allowed"
          >
            <AppIcon v-if="uploadingRecordPhoto" name="loader" :size="15" class="animate-spin" />
            <AppIcon v-else name="camera" :size="15" />
            <span>{{ uploadingRecordPhoto ? '正在上传实拍照片...' : '上传当日真人对镜 OOTD 实拍（支持 AI 看图体检）' }}</span>
          </button>

          <!-- Inline AI Real-Photo Critique Card -->
          <div
            v-if="ootdCritique && ootdCritique.historyId === selectedRecord.id"
            class="p-4 rounded-2xl bg-[#FAFAFA] border border-black/15 space-y-3"
          >
            <div class="flex items-center justify-between gap-2">
              <div class="flex items-center gap-2">
                <span class="font-mono text-2xl font-semibold text-black leading-none">
                  {{ ootdCritique.result.overallScore }}
                </span>
                <span class="font-mono text-[11px] text-[#666666]">/ 100</span>
                <span
                  class="px-2 py-0.5 rounded-full text-[10px] font-mono"
                  :class="ootdCritique.result.usedRealVlm ? 'bg-black text-white' : 'bg-white border border-black/10 text-[#555555]'"
                >
                  {{ ootdCritique.result.usedRealVlm ? 'VLM 看图点评' : '规则体检' }}
                </span>
              </div>
              <button
                type="button"
                @click="ootdCritique = null"
                class="w-6 h-6 rounded-full hover:bg-black/10 flex items-center justify-center text-[#555555]"
              >
                <AppIcon name="x" :size="13" />
              </button>
            </div>

            <p class="text-[13px] font-medium text-black">“{{ ootdCritique.result.headline }}”</p>

            <div class="grid grid-cols-3 gap-2">
              <div
                v-for="bar in [
                  { label: '色彩', score: ootdCritique.result.colorScore },
                  { label: '比例', score: ootdCritique.result.proportionScore },
                  { label: '场合', score: ootdCritique.result.sceneScore },
                ]"
                :key="bar.label"
                class="p-2 rounded-xl bg-white border border-black/[0.06]"
              >
                <div class="flex items-center justify-between text-[11px]">
                  <span class="text-[#666666]">{{ bar.label }}</span>
                  <span class="font-mono font-semibold text-black">{{ bar.score }}</span>
                </div>
                <div class="h-1 rounded-full bg-[#EFEFEF] mt-1.5 overflow-hidden">
                  <div class="h-full bg-black rounded-full" :style="{ width: `${bar.score}%` }" />
                </div>
              </div>
            </div>

            <ul class="space-y-1 text-[12px] text-[#222222]">
              <li v-for="(h, idx) in ootdCritique.result.highlights" :key="'h-' + idx" class="flex items-start gap-1.5">
                <AppIcon name="check" :size="12" class="mt-0.5 shrink-0 text-black" />
                <span>{{ h }}</span>
              </li>
              <li v-for="(imp, idx) in ootdCritique.result.improvements" :key="'i-' + idx" class="flex items-start gap-1.5 text-[#555555]">
                <AppIcon name="sparkles" :size="12" class="mt-0.5 shrink-0 text-black" />
                <span>{{ imp }}</span>
              </li>
            </ul>

            <div
              v-if="ootdCritique.result.suggestedSwap"
              class="p-2.5 rounded-xl bg-white border border-black/10 text-[11.5px]"
            >
              <span class="font-semibold text-black">衣橱换搭建议：{{ ootdCritique.result.suggestedSwap.itemName }}</span>
              <span class="text-[#666666]"> — {{ ootdCritique.result.suggestedSwap.reason }}</span>
            </div>
            <p v-if="ootdCritique.result.notice" class="text-[10.5px] text-[#777777]">{{ ootdCritique.result.notice }}</p>
          </div>

          <!-- Worn Wardrobe Pieces -->
          <div v-if="selectedRecord.items.length > 0" class="grid grid-cols-4 gap-2.5">
            <div
              v-for="item in selectedRecord.items"
              :key="item.id"
              @click="wardrobeStore.drawerItem = item"
              class="rounded-2xl bg-[#F5F5F5] border border-black/[0.06] p-2 flex flex-col items-center cursor-pointer hover:border-black/40 transition"
            >
              <div class="w-full aspect-square">
                <GarmentVisual :image-url="item.processedImageUrl" :svg-type="item.svgType" :color-hex="item.colorHex" :alt="item.name" />
              </div>
              <span class="text-[10.5px] text-black truncate w-full text-center mt-1">{{ item.name }}</span>
            </div>
          </div>

          <div v-if="selectedRecord.aiReasoning" class="p-3.5 rounded-2xl bg-[#FAFAFA] border border-black/[0.06]">
            <div class="text-[11.5px] font-semibold text-[#666666] mb-1">当日 AI 搭配记录</div>
            <p class="text-[12.5px] text-[#333333] leading-relaxed">{{ selectedRecord.aiReasoning }}</p>
          </div>

          <div>
            <div class="flex items-center justify-between mb-1.5">
              <label class="text-[12px] font-medium text-[#666666]">个人穿搭心得笔记</label>
              <div class="flex items-center gap-0.5 text-black text-sm">
                <button
                  v-for="star in 5"
                  :key="star"
                  type="button"
                  :disabled="savingRating"
                  @click="setRating(star)"
                  class="hover:scale-110 transition disabled:opacity-50"
                >
                  {{ star <= (selectedRecord.feedbackScore || 0) ? '★' : '☆' }}
                </button>
              </div>
            </div>
            <textarea
              v-model="noteDraft"
              rows="2"
              class="w-full px-3.5 py-2.5 rounded-2xl bg-[#FAFAFA] border border-black/[0.08] focus:border-black focus:bg-white outline-none text-[13px] resize-none"
            />
            <div class="flex justify-end mt-2">
              <button
                type="button"
                @click="saveNotes"
                :disabled="savingNotes"
                class="inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-full bg-black hover:bg-[#262626] text-white text-[12px] font-medium transition disabled:opacity-55 disabled:cursor-not-allowed"
              >
                <AppIcon v-if="savingNotes" name="loader" :size="12" class="animate-spin" />
                {{ savingNotes ? '保存中…' : '保存心得' }}
              </button>
            </div>
          </div>
        </div>

        <!-- State B: Empty Date -> Inline Real-Photo OOTD Check-in Card -->
        <div v-else class="space-y-4">
          <div class="pb-3 border-b border-black/[0.06] flex items-center justify-between gap-2">
            <div>
              <span class="font-mono text-[12px] text-black font-semibold">{{ selectedDate }}</span>
              <h3 class="text-lg font-semibold text-black mt-0.5">上传当日真人 OOTD 实拍打卡</h3>
            </div>
            <button
              type="button"
              @click="router.push('/lookbook')"
              class="text-[11.5px] text-[#666666] hover:text-black underline underline-offset-4"
            >
              或去画板排搭配
            </button>
          </div>

          <input
            ref="checkinFileInputRef"
            type="file"
            accept="image/png,image/jpeg,image/webp"
            class="hidden"
            @change="onSelectCheckinPhoto"
          />

          <!-- Photo Upload / Preview Area -->
          <div v-if="checkin.previewUrl" class="space-y-2">
            <div class="relative rounded-2xl overflow-hidden bg-[#F5F5F5] border border-black/[0.08]">
              <img :src="checkin.previewUrl" alt="OOTD 预览" class="w-full max-h-[260px] object-cover" />
              <button
                type="button"
                @click="checkinFileInputRef?.click()"
                class="absolute bottom-2.5 right-2.5 px-3 py-1 rounded-full bg-black/75 backdrop-blur text-white text-[11px] font-medium hover:bg-black transition"
              >
                换一张
              </button>
            </div>
            <button
              type="button"
              @click="autoDetectWornItems"
              :disabled="checkin.detecting"
              class="w-full py-2 px-3 rounded-full border border-black text-black hover:bg-black hover:text-white text-[12px] font-medium flex items-center justify-center gap-1.5 transition disabled:opacity-55 disabled:cursor-not-allowed"
            >
              <AppIcon v-if="checkin.detecting" name="loader" :size="13" class="animate-spin" />
              <AppIcon v-else name="sparkles" :size="13" />
              {{ checkin.detecting ? 'AI 正在识别你今天穿了哪几件...' : 'AI 自动识别图中所穿衣橱单品' }}
            </button>
          </div>

          <button
            v-else
            type="button"
            @click="checkinFileInputRef?.click()"
            class="w-full py-10 px-4 rounded-2xl border border-dashed border-black/25 hover:border-black bg-[#FAFAFA] hover:bg-[#F5F5F5] flex flex-col items-center justify-center gap-2 transition"
          >
            <span class="w-10 h-10 rounded-full bg-black text-white flex items-center justify-center">
              <AppIcon name="camera" :size="18" />
            </span>
            <span class="text-[13px] font-medium text-black">点击上传对镜拍 / 全身出街实拍</span>
            <span class="text-[11.5px] text-[#666666]">写入月历实拍墙 · 可关联衣橱单品自动累计 CPW</span>
          </button>

          <!-- Compact Wardrobe Item Picker -->
          <div v-if="wardrobeStore.items.length > 0" class="space-y-2">
            <div class="flex items-center justify-between text-[12px]">
              <span class="font-medium text-black">关联今日所穿单品</span>
              <span class="font-mono text-[11px] text-[#666666]">已选 {{ checkin.selectedItemIds.length }} 件</span>
            </div>
            <div class="grid grid-cols-4 gap-2 max-h-[156px] overflow-y-auto pr-1">
              <button
                v-for="item in wardrobeStore.items"
                :key="item.id"
                type="button"
                @click="toggleCheckinItem(item.id)"
                class="relative rounded-xl p-1.5 border text-left transition flex flex-col items-center"
                :class="
                  checkin.selectedItemIds.includes(item.id)
                    ? 'border-black bg-[#F5F5F5]'
                    : 'border-black/[0.06] bg-white hover:border-black/25'
                "
              >
                <span
                  v-if="checkin.selectedItemIds.includes(item.id)"
                  class="absolute top-1 right-1 w-4 h-4 rounded-full bg-black text-white flex items-center justify-center z-10"
                >
                  <AppIcon name="check" :size="10" />
                </span>
                <div class="w-full aspect-square">
                  <GarmentVisual :image-url="item.processedImageUrl" :svg-type="item.svgType" :color-hex="item.colorHex" :alt="item.name" />
                </div>
                <span class="text-[10px] text-black truncate w-full text-center mt-0.5">{{ item.name }}</span>
              </button>
            </div>
          </div>

          <div class="space-y-2.5">
            <input
              v-model="checkin.themeTitle"
              type="text"
              maxlength="40"
              placeholder="今日主题，例如：燕麦西装秋日咖啡 OOTD（可留空）"
              class="w-full px-3.5 py-2 rounded-xl bg-[#FAFAFA] border border-black/[0.08] focus:border-black focus:bg-white outline-none text-[12.5px]"
            />
            <div class="flex flex-wrap gap-1.5">
              <button
                v-for="sc in CHECKIN_SCENES"
                :key="sc"
                type="button"
                @click="checkin.sceneTag = sc"
                class="px-2.5 py-1 rounded-full text-[11px] border transition"
                :class="
                  checkin.sceneTag === sc
                    ? 'bg-black text-white border-black'
                    : 'bg-white text-[#555555] border-black/10 hover:border-black'
                "
              >
                {{ sc }}
              </button>
            </div>
            <textarea
              v-model="checkin.notes"
              rows="2"
              placeholder="写一句今天的穿着体感或出片心得..."
              class="w-full px-3.5 py-2 rounded-xl bg-[#FAFAFA] border border-black/[0.08] focus:border-black focus:bg-white outline-none text-[12.5px] resize-none"
            />
          </div>

          <button
            type="button"
            @click="submitOotdCheckin"
            :disabled="!checkin.file || checkin.submitting"
            class="w-full py-2.5 rounded-full bg-black hover:bg-[#262626] text-white text-[13px] font-medium transition inline-flex items-center justify-center gap-1.5 disabled:opacity-45 disabled:cursor-not-allowed"
          >
            <AppIcon v-if="checkin.submitting" name="loader" :size="14" class="animate-spin" />
            {{ checkin.submitting ? '正在保存实拍打卡...' : '保存 OOTD 打卡到穿搭日历' }}
          </button>
        </div>
      </div>
    </div>

    <!-- Favorites Grid -->
    <div v-else>
      <div v-if="lookbookStore.favorites.length > 0" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
        <div
          v-for="look in lookbookStore.favorites"
          :key="look.id"
          class="bg-white rounded-3xl border border-black/[0.08] p-5 flex flex-col justify-between"
        >
          <div>
            <div class="flex items-start justify-between gap-2 mb-3">
              <div>
                <span class="text-[11px] px-2 py-0.5 rounded-full bg-[#F5F5F5] text-[#666666]">{{ look.sceneTag }}</span>
                <h4 class="text-[17px] font-semibold text-black mt-1">{{ look.themeTitle }}</h4>
              </div>
              <button
                @click="removeFavorite(look.id)"
                :disabled="removingFavId === look.id"
                class="text-black hover:scale-110 transition disabled:opacity-50"
                title="取消收藏"
              >
                <AppIcon v-if="removingFavId === look.id" name="loader" :size="16" class="animate-spin" />
                <AppIcon v-else name="heart" :size="18" fill="#000000" />
              </button>
            </div>
            <div class="grid grid-cols-4 gap-2 p-3 rounded-2xl flatlay-canvas border border-black/[0.06] mb-3">
              <div
                v-for="(s, idx) in look.slots.slice(0, 4)"
                :key="idx"
                class="aspect-square rounded-xl bg-white/90 p-1.5 flex items-center justify-center"
              >
                <GarmentVisual :image-url="s.item.processedImageUrl" :svg-type="s.item.svgType" :color-hex="s.item.colorHex" />
              </div>
            </div>
            <p class="text-[12.5px] text-[#666666] leading-relaxed">{{ look.aiReasoning }}</p>
          </div>
          <div class="mt-4 pt-3 border-t border-black/[0.06] flex items-center justify-between">
            <span class="font-mono text-[12px] text-black font-semibold">匹配度 {{ look.matchScore }}%</span>
            <button
              @click="openLookInEngine(look)"
              class="px-3.5 py-1.5 rounded-full bg-black text-white text-[12px] font-medium hover:bg-[#262626] transition"
            >
              在画板中打开 →
            </button>
          </div>
        </div>
      </div>
      <div v-else class="bg-white rounded-3xl border border-black/[0.08] py-14 text-center text-[13px] text-[#666666]">
        暂无收藏的穿搭方案
      </div>
    </div>
  </section>
</template>
