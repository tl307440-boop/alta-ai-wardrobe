import http, { unwrap } from './http';
import type {
  AiModelConfig,
  AiTestResult,
  AuthResponse,
  CollageNode,
  HistoryRecord,
  InspirationAnalyzeResult,
  InspoFeedResponse,
  InspoLikeToggle,
  ItemDetailResponse,
  ItemRecognitionResult,
  ItemsDetectionResult,
  OutfitCritiqueResult,
  StoredItemImage,
  OutfitPlanVo,
  PreferenceAnalytics,
  ProductItem,
  TryOnJob,
  TryOnResult,
  UserInfo,
  WardrobeItem,
  WeatherInfo,
} from '@/types';

export const authApi = {
  login: (payload: { username: string; password: string }) =>
    unwrap<AuthResponse>(http.post('/auth/login', payload)),
  register: (payload: {
    username: string;
    nickname: string;
    password: string;
    defaultCity: string;
    loadStarterPack: boolean;
  }) => unwrap<AuthResponse>(http.post('/auth/register', payload)),
  me: () => unwrap<UserInfo>(http.get('/auth/me')),
};

export const wardrobeApi = {
  list: (params?: { category?: string; color?: string; season?: string; keyword?: string }) =>
    unwrap<WardrobeItem[]>(http.get('/wardrobe/items', { params })),
  create: (payload: Partial<WardrobeItem> & { aiRawResult?: string }) =>
    unwrap<WardrobeItem>(http.post('/wardrobe/items', payload)),
  detail: (id: number) => unwrap<ItemDetailResponse>(http.get(`/wardrobe/items/${id}`)),
  update: (id: number, payload: Partial<WardrobeItem>) =>
    unwrap<WardrobeItem>(http.put(`/wardrobe/items/${id}`, payload)),
  remove: (id: number) => unwrap<void>(http.delete(`/wardrobe/items/${id}`)),
};

export const preferenceApi = {
  get: () => unwrap<PreferenceAnalytics>(http.get('/preferences')),
  update: (payload: {
    defaultCity?: string;
    favoriteStyles?: string[];
    favoriteColors?: string[];
    dislikedColors?: string[];
    preferredScenes?: string[];
    budgetLimit?: number;
    bodyShape?: string;
    colorSeason?: string;
    heightCm?: number;
  }) => unwrap<PreferenceAnalytics>(http.put('/preferences', payload)),
};

export const aiConfigApi = {
  get: () => unwrap<AiModelConfig>(http.get('/ai-config')),
  save: (payload: {
    providerName: string;
    baseUrl: string;
    apiKey?: string;
    clearApiKey?: boolean;
    visionModel: string;
    visionBaseUrl?: string;
    visionApiKey?: string;
    clearVisionApiKey?: boolean;
    chatModel: string;
    imageModel?: string;
    imageBaseUrl?: string;
    imageApiKey?: string;
    clearImageApiKey?: boolean;
    enabled: boolean;
    vtonEnabled?: boolean;
    vtonProvider?: string;
    vtonModel?: string;
    vtonApiKey?: string;
    clearVtonApiKey?: boolean;
    vtonPublicBaseUrl?: string;
  }) => unwrap<AiModelConfig>(http.put('/ai-config', payload)),
  test: (payload?: {
    channel?: string;
    baseUrl?: string;
    apiKey?: string;
    chatModel?: string;
    visionBaseUrl?: string;
    visionApiKey?: string;
    visionModel?: string;
    imageBaseUrl?: string;
    imageApiKey?: string;
    imageModel?: string;
    vtonEnabled?: boolean;
    vtonApiKey?: string;
    vtonModel?: string;
  }) => unwrap<AiTestResult>(http.post('/ai-config/test', payload || {})),
  uploadModelReference: (file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    return unwrap<AiModelConfig>(
      http.post('/ai-config/model-reference', fd, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
    );
  },
  clearModelReference: () => unwrap<AiModelConfig>(http.delete('/ai-config/model-reference')),
};

export const weatherApi = {
  current: (city?: string, refresh = false) =>
    unwrap<WeatherInfo>(
      http.get('/weather/current', {
        params: {
          ...(city ? { city } : {}),
          ...(refresh ? { refresh: true } : {}),
        },
      })
    ),
};

export const visionApi = {
  recognizeItem: (formData: FormData) =>
    unwrap<ItemRecognitionResult>(
      http.post('/vision/recognize-item', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
    ),
  detectItems: (formData: FormData) =>
    unwrap<ItemsDetectionResult>(
      http.post('/vision/detect-items', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
    ),
  storeItemImage: (formData: FormData) =>
    unwrap<StoredItemImage>(
      http.post('/vision/store-item-image', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
    ),
  analyzeInspiration: (formData: FormData) =>
    unwrap<InspirationAnalyzeResult>(
      http.post('/vision/analyze-inspiration', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
    ),
};

export const outfitApi = {
  recommend: (payload: {
    triggerType?: string;
    sceneTag?: string;
    userQuery?: string;
    anchorItemId?: number;
    budgetLimit?: number;
    refineInstruction?: string;
    basePlanId?: number;
    weatherTemp?: number;
    weatherCondition?: string;
    weatherIcon?: string;
    weatherCity?: string;
    reviveIdle?: boolean;
    excludeItemIds?: number[];
  }) => unwrap<OutfitPlanVo>(http.post('/outfit/recommend', payload)),
  swapSlot: (planId: number, payload: { category: string; excludeItemIds?: number[] }) =>
    unwrap<OutfitPlanVo>(http.post(`/outfit/plans/${planId}/swap-slot`, payload)),
  saveCollageLayout: (planId: number, nodes: CollageNode[]) =>
    unwrap<OutfitPlanVo>(http.put(`/outfit/plans/${planId}/collage-layout`, { nodes })),
  addPlanItem: (planId: number, itemId: number) =>
    unwrap<OutfitPlanVo>(http.post(`/outfit/plans/${planId}/items`, { itemId })),
  removePlanItem: (planId: number, itemId: number) =>
    unwrap<OutfitPlanVo>(http.delete(`/outfit/plans/${planId}/items/${itemId}`)),
  generateTryOn: (
    planId: number,
    payload?: {
      stylePreset?: string;
      customPrompt?: string;
      useReferencePhoto?: boolean;
      generationMode?: 'auto' | 'hd' | 'chat_stream';
      modelPreset?: string;
      upperGarmentPreference?: 'auto' | 'outerwear' | 'top';
      clientOrigin?: string;
      enableQualityCheck?: boolean;
    }
  ) =>
    unwrap<TryOnResult>(
      http.post(`/outfit/plans/${planId}/try-on`, payload || {}, { timeout: 160000 })
    ),
  submitTryOnJob: (
    planId: number,
    payload?: {
      stylePreset?: string;
      customPrompt?: string;
      useReferencePhoto?: boolean;
      generationMode?: 'auto' | 'hd' | 'chat_stream';
      modelPreset?: string;
      upperGarmentPreference?: 'auto' | 'outerwear' | 'top';
      clientOrigin?: string;
      enableQualityCheck?: boolean;
    }
  ) => unwrap<TryOnJob>(http.post(`/outfit/plans/${planId}/try-on/jobs`, payload || {})),
  listTryOnJobs: (planId?: number, limit = 18) =>
    unwrap<TryOnJob[]>(
      http.get('/outfit/try-on/jobs', {
        params: { ...(planId ? { planId } : {}), limit },
      })
    ),
  getTryOnJob: (jobId: number) => unwrap<TryOnJob>(http.get(`/outfit/try-on/jobs/${jobId}`)),
  applyTryOnJobCover: (jobId: number, planId?: number) =>
    unwrap<OutfitPlanVo>(
      http.post(`/outfit/try-on/jobs/${jobId}/apply-cover`, null, {
        params: planId ? { planId } : undefined,
      })
    ),
  deleteTryOnJob: (jobId: number) => unwrap<void>(http.delete(`/outfit/try-on/jobs/${jobId}`)),
  getPlan: (planId: number) => unwrap<OutfitPlanVo>(http.get(`/outfit/plans/${planId}`)),
  listPlans: (favoriteOnly = false) =>
    unwrap<OutfitPlanVo[]>(http.get('/outfit/plans', { params: { favoriteOnly } })),
  toggleFavorite: (planId: number) =>
    unwrap<OutfitPlanVo>(http.patch(`/outfit/plans/${planId}/favorite`)),
  dislikePlan: (planId: number) =>
    unwrap<OutfitPlanVo>(http.post(`/outfit/plans/${planId}/dislike`)),
  deletePlan: (planId: number) => unwrap<void>(http.delete(`/outfit/plans/${planId}`)),
  listHistory: (month?: string) =>
    unwrap<HistoryRecord[]>(http.get('/outfit/history', { params: month ? { month } : undefined })),
  recordHistory: (payload: {
    planId?: number;
    themeTitle: string;
    wearDate: string;
    sceneTag?: string;
    weatherInfo?: string;
    weatherTemp?: number;
    weatherIcon?: string;
    itemIds: number[];
    aiReasoning?: string;
    feedbackScore?: number;
    notes?: string;
  }) => unwrap<HistoryRecord>(http.post('/outfit/history', payload)),
  updateHistory: (id: number, payload: { feedbackScore?: number; notes?: string }) =>
    unwrap<HistoryRecord>(http.put(`/outfit/history/${id}`, payload)),
  uploadHistoryPhoto: (id: number, file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    return unwrap<HistoryRecord>(
      http.post(`/outfit/history/${id}/photo`, fd, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
    );
  },
  deleteHistoryPhoto: (id: number) =>
    unwrap<HistoryRecord>(http.delete(`/outfit/history/${id}/photo`)),
  checkinOotd: (formData: FormData) =>
    unwrap<HistoryRecord>(
      http.post('/outfit/history/ootd-checkin', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
    ),
  critique: (formData: FormData) =>
    unwrap<OutfitCritiqueResult>(
      http.post('/outfit/critique', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: 60000,
      })
    ),
};

export const productApi = {
  list: (params?: { category?: string; maxPrice?: number }) =>
    unwrap<ProductItem[]>(http.get('/products', { params })),
};

export const inspoApi = {
  feed: (params?: { tab?: string; gender?: string; q?: string; likedOnly?: boolean }) =>
    unwrap<InspoFeedResponse>(http.get('/inspo/feed', { params })),
  toggleLike: (lookId: string) =>
    unwrap<InspoLikeToggle>(http.post(`/inspo/looks/${encodeURIComponent(lookId)}/like`)),
};
