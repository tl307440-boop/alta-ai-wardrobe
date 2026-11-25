import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { aiConfigApi, preferenceApi, productApi, wardrobeApi, weatherApi } from '@/api/modules';
import type {
  AiModelConfig,
  PreferenceAnalytics,
  ProductItem,
  WardrobeItem,
  WeatherInfo,
} from '@/types';
import { WEATHER_PRESETS } from '@/utils/constants';

export const useWardrobeStore = defineStore('wardrobe', () => {
  const items = ref<WardrobeItem[]>([]);
  const products = ref<ProductItem[]>([]);
  const preference = ref<PreferenceAnalytics>({
    defaultCity: '武汉',
    favoriteStyles: ['极简通勤', '温柔韩系'],
    favoriteColors: ['米白', '燕麦', '牛仔蓝'],
    dislikedColors: ['荧光绿'],
    preferredScenes: ['职场通勤', '周末约会'],
    budgetLimit: 300,
    styleWeights: {},
    categoryStats: [],
    styleAffinity: [],
    colorStats: [],
    idleRate: 0,
  });
  const aiConfig = ref<AiModelConfig>({
    providerName: 'dashscope',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    hasApiKey: false,
    apiKeyMasked: '',
    visionModel: 'qwen-vl-max',
    chatModel: 'qwen-plus',
    imageModel: 'gpt-image-1',
    modelReferenceUrl: null,
    enabled: true,
    serverDefaultAvailable: false,
  });

  const liveWeather = ref<WeatherInfo>({
    city: '武汉',
    temp: 18,
    condition: '多云',
    icon: 'cloud-sun',
    tip: '适宜叠穿',
    live: false,
  });
  // -1 means real backend weather; 0/1/2 means one of the 3 interactive weather simulator presets
  const weatherPresetIdx = ref<number>(0);
  const usingLiveWeather = ref<boolean>(true);

  const activeWeather = computed<WeatherInfo>(() => {
    if (usingLiveWeather.value) {
      return liveWeather.value;
    }
    const p = WEATHER_PRESETS[weatherPresetIdx.value] || WEATHER_PRESETS[0];
    return {
      city: preference.value.defaultCity || '武汉',
      temp: p.temp,
      condition: p.condition,
      icon: p.icon,
      tip: p.tip,
      seasonHint: p.seasonHint,
      live: false,
    };
  });

  // Global overlays
  const drawerItem = ref<WardrobeItem | null>(null);
  const activeProductModal = ref<ProductItem | null>(null);

  async function bootstrap() {
    const [wItems, pref, cfg, prods] = await Promise.all([
      wardrobeApi.list(),
      preferenceApi.get(),
      aiConfigApi.get(),
      productApi.list(),
    ]);
    items.value = wItems;
    preference.value = pref;
    aiConfig.value = cfg;
    products.value = prods;
    try {
      liveWeather.value = await weatherApi.current(pref.defaultCity);
      usingLiveWeather.value = true;
    } catch {
      usingLiveWeather.value = false;
    }
  }

  async function refreshItems() {
    items.value = await wardrobeApi.list();
    preference.value = await preferenceApi.get();
  }

  async function createItem(payload: Partial<WardrobeItem> & { aiRawResult?: string }) {
    const created = await wardrobeApi.create(payload);
    items.value.unshift(created);
    preference.value = await preferenceApi.get();
    return created;
  }

  async function updateItem(id: number, payload: Partial<WardrobeItem>) {
    const updated = await wardrobeApi.update(id, payload);
    const idx = items.value.findIndex((i) => i.id === id);
    if (idx > -1) items.value[idx] = updated;
    if (drawerItem.value?.id === id) drawerItem.value = updated;
    preference.value = await preferenceApi.get();
    return updated;
  }

  async function deleteItem(id: number) {
    await wardrobeApi.remove(id);
    items.value = items.value.filter((i) => i.id !== id);
    if (drawerItem.value?.id === id) drawerItem.value = null;
    preference.value = await preferenceApi.get();
  }

  async function updatePreference(payload: {
    defaultCity?: string;
    favoriteStyles?: string[];
    favoriteColors?: string[];
    dislikedColors?: string[];
    preferredScenes?: string[];
    budgetLimit?: number;
    bodyShape?: string;
    colorSeason?: string;
    heightCm?: number;
  }) {
    const prevCity = preference.value.defaultCity;
    preference.value = await preferenceApi.update(payload);
    if (payload.defaultCity && payload.defaultCity !== prevCity) {
      try {
        liveWeather.value = await weatherApi.current(payload.defaultCity);
        usingLiveWeather.value = true;
      } catch {
        // ignore
      }
    }
    return preference.value;
  }

  async function saveAiConfig(payload: {
    providerName: string;
    baseUrl: string;
    apiKey?: string;
    clearApiKey?: boolean;
    visionModel: string;
    chatModel: string;
    imageModel?: string;
    enabled: boolean;
  }) {
    aiConfig.value = await aiConfigApi.save(payload);
    return aiConfig.value;
  }

  async function uploadModelReference(file: File) {
    aiConfig.value = await aiConfigApi.uploadModelReference(file);
    return aiConfig.value;
  }

  async function clearModelReference() {
    aiConfig.value = await aiConfigApi.clearModelReference();
    return aiConfig.value;
  }

  function selectWeatherPreset(idx: number) {
    usingLiveWeather.value = false;
    weatherPresetIdx.value = idx;
  }

  async function useLiveWeather(forceRefresh = false) {
    usingLiveWeather.value = true;
    try {
      liveWeather.value = await weatherApi.current(preference.value.defaultCity, forceRefresh);
    } catch {
      // keep existing liveWeather
    }
    return liveWeather.value;
  }

  function cycleWeatherPreset() {
    if (usingLiveWeather.value) {
      usingLiveWeather.value = false;
      weatherPresetIdx.value = 0;
    } else if (weatherPresetIdx.value < WEATHER_PRESETS.length - 1) {
      weatherPresetIdx.value += 1;
    } else {
      usingLiveWeather.value = true;
    }
  }

  function reset() {
    items.value = [];
    drawerItem.value = null;
    activeProductModal.value = null;
  }

  return {
    items,
    products,
    preference,
    aiConfig,
    liveWeather,
    weatherPresetIdx,
    usingLiveWeather,
    activeWeather,
    drawerItem,
    activeProductModal,
    bootstrap,
    refreshItems,
    createItem,
    updateItem,
    deleteItem,
    updatePreference,
    saveAiConfig,
    uploadModelReference,
    clearModelReference,
    selectWeatherPreset,
    useLiveWeather,
    cycleWeatherPreset,
    reset,
  };
});
