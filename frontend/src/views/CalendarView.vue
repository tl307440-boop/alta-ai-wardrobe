<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useLookbookStore } from '@/stores/lookbook';
import { useToastStore } from '@/stores/toast';
import type { OutfitPlanVo } from '@/types';
import AppIcon from '@/components/AppIcon.vue';
import GarmentVisual from '@/components/GarmentVisual.vue';

const route = useRoute();
const router = useRouter();
const wardrobeStore = useWardrobeStore();
const lookbookStore = useLookbookStore();
const toast = useToastStore();

const subTab = ref<'calendar' | 'favorites'>(route.query.tab === 'favorites' ? 'favorites' : 'calendar');

const now = new Date();
const viewYear = ref(now.getFullYear());
const viewMonth = ref(now.getMonth() + 1);

const todayStr = computed(() => {
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
});

const calendarCells = computed(() => {
  const firstDow = new Date(viewYear.value, viewMonth.value - 1, 1).getDay();
  const daysInMonth = new Date(viewYear.value, viewMonth.value, 0).getDate();
  const cells: Array<{ blank?: boolean; dayNum?: number; dateStr?: string; record?: (typeof lookbookStore.history)[number] | null }> = [];
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

const selectedRecord = computed(
  () =>
    lookbookStore.history.find((h) => h.wearDate === lookbookStore.selectedCalendarDate) ||
    lookbookStore.history[0] ||
    null
);

const noteDraft = ref('');
watch(
  selectedRecord,
  (rec) => {
    noteDraft.value = rec?.notes || '';
  },
  { immediate: true }
);

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
  if (!selectedRecord.value) return;
  await lookbookStore.updateHistoryRecord(selectedRecord.value.id, { feedbackScore: star });
  toast.show(`已更新满意度为 ${star} 星，隐性偏好权重已同步演进`);
}

async function saveNotes() {
  if (!selectedRecord.value) return;
  await lookbookStore.updateHistoryRecord(selectedRecord.value.id, { notes: noteDraft.value });
  toast.show('个人穿搭心得笔记已保存');
}

async function removeFavorite(id: number) {
  await lookbookStore.toggleFavorite(id);
  toast.show('已取消收藏');
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
        <div class="flex items-center justify-between mb-5">
          <div>
            <h3 class="text-xl font-semibold text-black">{{ viewYear }}年{{ viewMonth }}月</h3>
            <p class="text-[12px] text-[#666666] mt-0.5">
              本月已记录 <span class="font-mono text-black font-semibold">{{ monthRecordCount }}</span> 天穿搭档案
            </p>
          </div>
          <div class="flex items-center gap-1.5">
            <button
              @click="shiftMonth(-1)"
              class="w-8 h-8 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] text-black flex items-center justify-center transition"
            >
              <AppIcon name="chevron-left" :size="15" />
            </button>
            <span class="px-3 py-1 rounded-full bg-[#F5F5F5] border border-black/[0.08] text-[11.5px] text-[#666666] font-mono">
              今天 {{ todayStr.slice(5).replace('-', '.') }}
            </span>
            <button
              @click="shiftMonth(1)"
              class="w-8 h-8 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] text-black flex items-center justify-center transition"
            >
              <AppIcon name="chevron-right" :size="15" />
            </button>
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
              @click="cell.record && (lookbookStore.selectedCalendarDate = cell.dateStr!)"
              class="aspect-square rounded-2xl p-1.5 sm:p-2 flex flex-col justify-between text-left border transition relative"
              :class="[
                cell.record
                  ? selectedRecord?.wearDate === cell.dateStr
                    ? 'bg-black text-white border-black shadow-md'
                    : 'bg-[#FAFAFA] hover:bg-[#F5F5F5] border-black/[0.08] cursor-pointer'
                  : 'bg-transparent border-transparent text-[#666666]/60 cursor-default',
                cell.dateStr === todayStr && selectedRecord?.wearDate !== cell.dateStr ? 'ring-2 ring-black/40' : '',
              ]"
            >
              <div class="flex items-center justify-between w-full">
                <span class="font-mono text-[12px] font-semibold">{{ cell.dayNum }}</span>
                <span
                  v-if="cell.record"
                  class="hidden sm:inline font-mono text-[10px]"
                  :class="selectedRecord?.wearDate === cell.dateStr ? 'text-white/70' : 'text-black'"
                >
                  {{ cell.record.weatherTemp }}°
                </span>
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
                <AppIcon :name="cell.record.weatherIcon || 'cloud-sun'" :size="12" class="hidden sm:inline opacity-80" />
              </div>
            </button>
          </template>
        </div>
      </div>

      <!-- Daily Archive Panel -->
      <div class="lg:col-span-5 bg-white rounded-3xl border border-black/[0.08] p-6 flex flex-col justify-between">
        <div v-if="selectedRecord">
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

          <div class="grid grid-cols-4 gap-2.5 my-5">
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

          <div v-if="selectedRecord.aiReasoning" class="p-3.5 rounded-2xl bg-[#FAFAFA] border border-black/[0.06] mb-4">
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
                  @click="setRating(star)"
                  class="hover:scale-110 transition"
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
                @click="saveNotes"
                class="px-3.5 py-1.5 rounded-full bg-black hover:bg-[#262626] text-white text-[12px] font-medium transition"
              >
                保存心得
              </button>
            </div>
          </div>
        </div>

        <div v-else class="py-16 text-center text-[#666666] text-[13px] space-y-2">
          <p>当前账号暂无穿搭历史记录</p>
          <button @click="router.push('/lookbook')" class="px-4 py-2 rounded-full bg-black text-white text-[12.5px]">
            去画板记录今日穿搭
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
              <button @click="removeFavorite(look.id)" class="text-black hover:scale-110 transition" title="取消收藏">
                <AppIcon name="heart" :size="18" fill="#000000" />
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
