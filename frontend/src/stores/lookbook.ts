import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { outfitApi } from '@/api/modules';
import type { CollageNode, HistoryRecord, OutfitPlanVo, TryOnJob, TryOnResult } from '@/types';
import { useWardrobeStore } from './wardrobe';
import { useToastStore } from './toast';

const LAST_PLAN_ID_KEY = 'alta_last_lookbook_plan_id';
const ACTIVE_TRYON_JOB_KEY = 'alta_active_tryon_job_id';

export const useLookbookStore = defineStore('lookbook', () => {
  const currentPlan = ref<OutfitPlanVo | null>(null);
  const favorites = ref<OutfitPlanVo[]>([]);
  const history = ref<HistoryRecord[]>([]);
  const tryOnJobs = ref<TryOnJob[]>([]);
  const selectedTryOnJobId = ref<number | null>(null);
  const loading = ref(false);

  const triggerMode = ref<'weather' | 'nl' | 'anchor'>('weather');
  const reviveIdle = ref<boolean>(false);
  const budget = ref<number>(300);
  const nlQuery = ref<string>('');
  const anchorItemId = ref<number | null>(null);
  const selectedCalendarDate = ref<string>('');

  let pollTimer: number | null = null;
  let pollAttempt = 0;
  let visibilityBound = false;

  const activeTryOnJobs = computed(() =>
    tryOnJobs.value.filter((j) => j.status === 'QUEUED' || j.status === 'PROCESSING')
  );

  function isPlanGenerating(planId?: number | null) {
    if (!planId) return false;
    return activeTryOnJobs.value.some((j) => j.planId === planId);
  }

  function rememberPlan(plan: OutfitPlanVo | null) {
    currentPlan.value = plan;
    if (plan?.id) {
      try {
        localStorage.setItem(LAST_PLAN_ID_KEY, String(plan.id));
      } catch {
        // Ignore storage quota errors
      }
    }
  }

  function upsertJob(job: TryOnJob) {
    const idx = tryOnJobs.value.findIndex((item) => item.id === job.id);
    if (idx >= 0) {
      const next = [...tryOnJobs.value];
      next[idx] = job;
      tryOnJobs.value = next;
    } else {
      tryOnJobs.value = [job, ...tryOnJobs.value].slice(0, 24);
    }
  }

  function syncActiveJobStorage() {
    try {
      const firstActive = activeTryOnJobs.value[0];
      if (firstActive) {
        localStorage.setItem(ACTIVE_TRYON_JOB_KEY, String(firstActive.id));
      } else {
        localStorage.removeItem(ACTIVE_TRYON_JOB_KEY);
      }
    } catch {
      // Ignore storage errors
    }
  }

  function clearPollTimer() {
    if (pollTimer !== null) {
      window.clearTimeout(pollTimer);
      pollTimer = null;
    }
  }

  function handleVisibilityChange() {
    if (typeof document === 'undefined') return;
    if (document.hidden) {
      clearPollTimer();
    } else if (activeTryOnJobs.value.length > 0) {
      pollAttempt = 0;
      void pollActiveTryOnJobs();
    }
  }

  function ensureVisibilityListener() {
    if (!visibilityBound && typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', handleVisibilityChange);
      visibilityBound = true;
    }
  }

  function scheduleNextPoll() {
    clearPollTimer();
    if (activeTryOnJobs.value.length === 0) {
      syncActiveJobStorage();
      return;
    }
    if (typeof document !== 'undefined' && document.hidden) {
      return;
    }
    const delay = pollAttempt < 4 ? 1500 : pollAttempt < 10 ? 2500 : 3500;
    pollTimer = window.setTimeout(() => {
      void pollActiveTryOnJobs();
    }, delay);
  }

  async function pollActiveTryOnJobs() {
    const activeList = [...activeTryOnJobs.value];
    if (activeList.length === 0) {
      clearPollTimer();
      syncActiveJobStorage();
      return;
    }
    pollAttempt++;
    const toast = useToastStore();

    for (const prev of activeList) {
      try {
        const latest = await outfitApi.getTryOnJob(prev.id);
        upsertJob(latest);

        if (latest.status === 'SUCCEEDED') {
          if (currentPlan.value && currentPlan.value.id === latest.planId && latest.previewImageUrl) {
            currentPlan.value = { ...currentPlan.value, tryOnImageUrl: latest.previewImageUrl };
          }
          selectedTryOnJobId.value = latest.id;
          toast.show(
            latest.usedRealImageModel
              ? 'AI 上身杂志预览图已生成并自动存档'
              : '上身示意预览图已生成并自动存档（刷新不丢失）'
          );
        } else if (latest.status === 'FAILED') {
          toast.show(latest.errorMessage || latest.notice || 'AI 上身预览生成失败');
        }
      } catch {
        // Keep polling gracefully on transient network hiccup
      }
    }

    syncActiveJobStorage();
    scheduleNextPoll();
  }

  function startTryOnPolling() {
    ensureVisibilityListener();
    pollAttempt = 0;
    scheduleNextPoll();
  }

  async function refreshTryOnJobs() {
    try {
      const jobs = await outfitApi.listTryOnJobs(undefined, 20);
      tryOnJobs.value = jobs;

      // Also check localStorage pointer in case a job was just submitted right before refresh
      let storedJobId: number | null = null;
      try {
        const raw = localStorage.getItem(ACTIVE_TRYON_JOB_KEY);
        if (raw) storedJobId = Number(raw) || null;
      } catch {
        storedJobId = null;
      }
      if (storedJobId && !jobs.some((j) => j.id === storedJobId)) {
        try {
          const single = await outfitApi.getTryOnJob(storedJobId);
          upsertJob(single);
        } catch {
          localStorage.removeItem(ACTIVE_TRYON_JOB_KEY);
        }
      }

      syncActiveJobStorage();
      if (activeTryOnJobs.value.length > 0) {
        startTryOnPolling();
      }
    } catch {
      // Non-fatal
    }
  }

  async function restorePlanById(planId: number) {
    try {
      const plan = await outfitApi.getPlan(planId);
      rememberPlan(plan);
      return plan;
    } catch {
      return null;
    }
  }

  async function bootstrap() {
    const wardrobeStore = useWardrobeStore();
    budget.value = Number(wardrobeStore.preference.budgetLimit) || 300;
    if (wardrobeStore.items.length > 0 && !anchorItemId.value) {
      anchorItemId.value = wardrobeStore.items[0].id;
    }
    const [favs, hist, allPlans] = await Promise.all([
      outfitApi.listPlans(true),
      outfitApi.listHistory(),
      outfitApi.listPlans(false),
    ]);
    favorites.value = favs;
    history.value = hist;
    if (hist.length > 0 && !selectedCalendarDate.value) {
      selectedCalendarDate.value = hist[0].wearDate;
    }

    await refreshTryOnJobs();

    if (!currentPlan.value) {
      // 1. Prefer URL ?planId=XX if present
      let urlPlanId: number | null = null;
      if (typeof window !== 'undefined') {
        const params = new URLSearchParams(window.location.search);
        const p = Number(params.get('planId'));
        if (p > 0) urlPlanId = p;
      }

      // 2. Otherwise prefer active generating job's plan or last visited plan from localStorage
      let preferredPlanId = urlPlanId || activeTryOnJobs.value[0]?.planId || null;
      if (!preferredPlanId && typeof window !== 'undefined') {
        try {
          const saved = Number(localStorage.getItem(LAST_PLAN_ID_KEY));
          if (saved > 0) preferredPlanId = saved;
        } catch {
          preferredPlanId = null;
        }
      }

      const matched = preferredPlanId ? allPlans.find((p) => p.id === preferredPlanId) : null;
      if (matched) {
        rememberPlan(matched);
      } else if (preferredPlanId) {
        const fetched = await restorePlanById(preferredPlanId);
        if (!fetched && allPlans.length > 0) {
          rememberPlan(allPlans[0]);
        }
      } else if (allPlans.length > 0) {
        // Restore user's latest persisted plan instead of overwriting with a brand-new empty plan
        rememberPlan(allPlans[0]);
      } else {
        await generateRecommendation({ triggerType: 'daily_weather' });
      }
    }
  }

  async function generateRecommendation(overrides?: {
    triggerType?: 'daily_weather' | 'nl_query' | 'anchor_item';
    sceneTag?: string;
    userQuery?: string;
    anchorItemId?: number;
    refineInstruction?: string;
    basePlanId?: number;
    reviveIdle?: boolean;
    excludeItemIds?: number[];
  }) {
    const wardrobeStore = useWardrobeStore();
    loading.value = true;
    try {
      const w = wardrobeStore.activeWeather;
      const plan = await outfitApi.recommend({
        triggerType:
          overrides?.triggerType ||
          (triggerMode.value === 'nl' ? 'nl_query' : triggerMode.value === 'anchor' ? 'anchor_item' : 'daily_weather'),
        sceneTag: overrides?.sceneTag,
        userQuery: overrides?.userQuery ?? nlQuery.value,
        anchorItemId: overrides?.anchorItemId ?? (anchorItemId.value || undefined),
        budgetLimit: budget.value,
        refineInstruction: overrides?.refineInstruction,
        basePlanId: overrides?.basePlanId,
        weatherTemp: w.temp,
        weatherCondition: w.condition,
        weatherIcon: w.icon,
        weatherCity: w.city,
        reviveIdle: overrides?.reviveIdle ?? reviveIdle.value,
        excludeItemIds: overrides?.excludeItemIds,
      });
      rememberPlan(plan);
      selectedTryOnJobId.value = null;
      return plan;
    } finally {
      loading.value = false;
    }
  }

  /**
   * Non-penalizing "换一换": generates a fresh weather-aware outfit avoiding current owned pieces
   * without lowering the user's style preference weights.
   */
  async function shuffleOutfit() {
    const excludeIds = (currentPlan.value?.slots || [])
      .filter((s) => !s.missing && s.item?.id > 0)
      .map((s) => s.item.id);
    return generateRecommendation({
      triggerType: 'daily_weather',
      excludeItemIds: excludeIds.length > 0 ? excludeIds : undefined,
    });
  }

  async function swapSlot(category: string, excludeItemIds: number[] = []) {
    if (!currentPlan.value) return null;
    loading.value = true;
    try {
      const updated = await outfitApi.swapSlot(currentPlan.value.id, { category, excludeItemIds });
      rememberPlan(updated);
      return updated;
    } finally {
      loading.value = false;
    }
  }

  async function saveCollageLayout(nodes: CollageNode[]) {
    if (!currentPlan.value) return null;
    const updated = await outfitApi.saveCollageLayout(currentPlan.value.id, nodes);
    rememberPlan(updated);
    favorites.value = favorites.value.map((f) => (f.id === updated.id ? updated : f));
    return updated;
  }

  async function submitTryOnJob(payload?: {
    stylePreset?: string;
    customPrompt?: string;
    useReferencePhoto?: boolean;
    generationMode?: 'auto' | 'hd' | 'chat_stream';
    modelPreset?: string;
    upperGarmentPreference?: 'auto' | 'outerwear' | 'top';
    clientOrigin?: string;
  }): Promise<TryOnJob | null> {
    if (!currentPlan.value) return null;
    const job = await outfitApi.submitTryOnJob(currentPlan.value.id, payload);
    upsertJob(job);
    selectedTryOnJobId.value = job.id;
    syncActiveJobStorage();
    if (job.status === 'QUEUED' || job.status === 'PROCESSING') {
      startTryOnPolling();
    } else if (job.status === 'SUCCEEDED' && job.previewImageUrl) {
      currentPlan.value = { ...currentPlan.value, tryOnImageUrl: job.previewImageUrl };
    }
    return job;
  }

  async function applyTryOnJobAsCover(jobId: number) {
    if (!currentPlan.value) return null;
    const updated = await outfitApi.applyTryOnJobCover(jobId, currentPlan.value.id);
    rememberPlan(updated);
    favorites.value = favorites.value.map((f) => (f.id === updated.id ? updated : f));
    return updated;
  }

  async function deleteTryOnJob(jobId: number) {
    await outfitApi.deleteTryOnJob(jobId);
    tryOnJobs.value = tryOnJobs.value.filter((j) => j.id !== jobId);
    if (selectedTryOnJobId.value === jobId) {
      selectedTryOnJobId.value = null;
    }
    syncActiveJobStorage();
  }

  async function generateTryOn(payload?: {
    stylePreset?: string;
    customPrompt?: string;
    useReferencePhoto?: boolean;
    generationMode?: 'auto' | 'hd' | 'chat_stream';
  }): Promise<TryOnResult | null> {
    if (!currentPlan.value) return null;
    const res = await outfitApi.generateTryOn(currentPlan.value.id, payload);
    if (currentPlan.value && res.previewImageUrl) {
      currentPlan.value = { ...currentPlan.value, tryOnImageUrl: res.previewImageUrl };
    }
    return res;
  }

  async function toggleFavorite(planId?: number) {
    const targetId = planId ?? currentPlan.value?.id;
    if (!targetId) return null;
    const updated = await outfitApi.toggleFavorite(targetId);
    if (currentPlan.value?.id === targetId) {
      rememberPlan(updated);
    }
    favorites.value = await outfitApi.listPlans(true);
    const wardrobeStore = useWardrobeStore();
    await wardrobeStore.refreshItems();
    return updated;
  }

  async function dislikePlan(planId?: number) {
    const targetId = planId ?? currentPlan.value?.id;
    if (!targetId) return null;
    loading.value = true;
    try {
      const replacement = await outfitApi.dislikePlan(targetId);
      rememberPlan(replacement);
      selectedTryOnJobId.value = null;
      const wardrobeStore = useWardrobeStore();
      await wardrobeStore.refreshItems();
      return replacement;
    } finally {
      loading.value = false;
    }
  }

  async function recordToday() {
    if (!currentPlan.value) return null;
    const wardrobeStore = useWardrobeStore();
    const w = wardrobeStore.activeWeather;
    const ownedIds = currentPlan.value.slots
      .filter((s) => !s.missing && s.item?.id > 0)
      .map((s) => s.item.id);
    const today = new Date();
    const yyyy = today.getFullYear();
    const mm = String(today.getMonth() + 1).padStart(2, '0');
    const dd = String(today.getDate()).padStart(2, '0');
    const wearDate = `${yyyy}-${mm}-${dd}`;

    const rec = await outfitApi.recordHistory({
      planId: currentPlan.value.id,
      themeTitle: currentPlan.value.themeTitle,
      wearDate,
      sceneTag: currentPlan.value.sceneTag,
      weatherInfo: `${w.condition} ${w.temp}℃`,
      weatherTemp: w.temp,
      weatherIcon: w.icon,
      itemIds: ownedIds,
      aiReasoning: currentPlan.value.aiReasoning,
      feedbackScore: 5,
      notes: '今日通过 AI 智能搭配画板一键采用。',
    });
    history.value = await outfitApi.listHistory();
    selectedCalendarDate.value = wearDate;
    await wardrobeStore.refreshItems();
    return rec;
  }

  async function updateHistoryRecord(id: number, payload: { feedbackScore?: number; notes?: string }) {
    const updated = await outfitApi.updateHistory(id, payload);
    const idx = history.value.findIndex((h) => h.id === id);
    if (idx > -1) history.value[idx] = updated;
    return updated;
  }

  async function uploadHistoryPhoto(id: number, file: File) {
    const updated = await outfitApi.uploadHistoryPhoto(id, file);
    const idx = history.value.findIndex((h) => h.id === id);
    if (idx > -1) history.value[idx] = updated;
    return updated;
  }

  async function deleteHistoryPhoto(id: number) {
    const updated = await outfitApi.deleteHistoryPhoto(id);
    const idx = history.value.findIndex((h) => h.id === id);
    if (idx > -1) history.value[idx] = updated;
    return updated;
  }

  async function checkinOotd(formData: FormData) {
    const wardrobeStore = useWardrobeStore();
    const rec = await outfitApi.checkinOotd(formData);
    history.value = await outfitApi.listHistory();
    selectedCalendarDate.value = rec.wearDate;
    await wardrobeStore.refreshItems();
    return rec;
  }

  function loadPlan(plan: OutfitPlanVo) {
    rememberPlan(JSON.parse(JSON.stringify(plan)));
    selectedTryOnJobId.value = null;
  }

  function reset() {
    clearPollTimer();
    currentPlan.value = null;
    favorites.value = [];
    history.value = [];
    tryOnJobs.value = [];
    selectedTryOnJobId.value = null;
    selectedCalendarDate.value = '';
    try {
      localStorage.removeItem(LAST_PLAN_ID_KEY);
      localStorage.removeItem(ACTIVE_TRYON_JOB_KEY);
    } catch {
      // Ignore
    }
  }

  return {
    currentPlan,
    favorites,
    history,
    tryOnJobs,
    activeTryOnJobs,
    selectedTryOnJobId,
    loading,
    triggerMode,
    reviveIdle,
    budget,
    nlQuery,
    anchorItemId,
    selectedCalendarDate,
    isPlanGenerating,
    bootstrap,
    restorePlanById,
    refreshTryOnJobs,
    generateRecommendation,
    shuffleOutfit,
    swapSlot,
    saveCollageLayout,
    submitTryOnJob,
    applyTryOnJobAsCover,
    deleteTryOnJob,
    generateTryOn,
    toggleFavorite,
    dislikePlan,
    recordToday,
    updateHistoryRecord,
    uploadHistoryPhoto,
    deleteHistoryPhoto,
    checkinOotd,
    loadPlan,
    reset,
  };
});
