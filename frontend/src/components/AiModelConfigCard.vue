<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { aiConfigApi } from '@/api/modules';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useToastStore } from '@/stores/toast';
import { AI_PROVIDER_PRESETS } from '@/utils/constants';
import type { AiTestResult } from '@/types';
import AppIcon from './AppIcon.vue';

const wardrobeStore = useWardrobeStore();
const toast = useToastStore();

const form = reactive({
  providerName: 'openai-relay',
  baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  apiKey: '',
  visionModel: 'qwen-vl-max',
  visionBaseUrl: '',
  visionApiKey: '',
  chatModel: 'qwen-plus',
  imageModel: 'gpt-image-1',
  imageBaseUrl: '',
  imageApiKey: '',
  enabled: true,
  vtonEnabled: false,
  vtonProvider: 'dashscope',
  vtonModel: 'qwen-image-2.1-pro',
  vtonApiKey: '',
  vtonPublicBaseUrl: '',
});

const showKey = ref(false);
const showVisionKey = ref(false);
const showImageKey = ref(false);
const showVtonKey = ref(false);
const showVisionOverride = ref(false);
const saving = ref(false);
const testing = ref(false);
const uploadingRef = ref(false);
const refFileInput = ref<HTMLInputElement | null>(null);
const testResult = ref<AiTestResult | null>(null);

const ACTIVE_VTON_MODELS = ['qwen-image-2.1-pro', 'qwen-image-2.1-turbo', 'qwen-image-3.0-pro', 'wan2.7-image-pro'] as const;
const normalizeClientVtonModel = (raw?: string | null) => {
  const v = (raw || '').trim().toLowerCase();
  return (ACTIVE_VTON_MODELS as readonly string[]).includes(v) ? v : 'qwen-image-2.1-pro';
};

const trimUrl = (url?: string | null) => (url || '').trim().replace(/\/+$/, '');
const sameHost = (a?: string | null, b?: string | null) => {
  const na = trimUrl(a).toLowerCase();
  const nb = trimUrl(b).toLowerCase();
  return Boolean(na && nb && na === nb);
};
const hostLabel = (url?: string | null) => {
  const clean = trimUrl(url);
  if (!clean) return '未配置';
  try {
    return new URL(clean).host || clean;
  } catch {
    return clean;
  }
};

watch(
  () => wardrobeStore.aiConfig,
  (cfg) => {
    if (!cfg) return;
    form.providerName = cfg.providerName || 'dashscope';
    form.baseUrl = cfg.baseUrl || 'https://dashscope.aliyuncs.com/compatible-mode/v1';
    form.visionModel = cfg.visionModel || 'qwen-vl-max';
    form.visionBaseUrl = cfg.visionBaseUrl || '';
    form.chatModel = cfg.chatModel || 'qwen-plus';
    form.imageModel = cfg.imageModel || 'gpt-image-1';
    form.imageBaseUrl = cfg.imageBaseUrl || '';
    form.enabled = cfg.enabled;
    form.vtonEnabled = Boolean(cfg.vtonEnabled);
    form.vtonProvider = cfg.vtonProvider || 'dashscope';
    form.vtonModel = normalizeClientVtonModel(cfg.vtonModel);
    form.vtonPublicBaseUrl = cfg.vtonPublicBaseUrl || '';
    if (cfg.visionBaseUrl || cfg.hasVisionApiKey) {
      showVisionOverride.value = true;
    }
  },
  { immediate: true }
);

const isMainDeepSeek = computed(() => /api\.deepseek\.com/i.test(form.baseUrl));
const effectiveVisionBase = computed(() => trimUrl(form.visionBaseUrl) || trimUrl(form.baseUrl));
const effectiveImageBase = computed(() => trimUrl(form.imageBaseUrl) || trimUrl(form.baseUrl));
const isImageHostUnsupported = computed(() => /api\.deepseek\.com/i.test(effectiveImageBase.value));

const hasMainKeyEffective = computed(() =>
  Boolean(form.apiKey.trim() || wardrobeStore.aiConfig.hasApiKey || wardrobeStore.aiConfig.serverDefaultAvailable)
);
const hasSavedVisionKeyForCurrent = computed(() =>
  Boolean(
    wardrobeStore.aiConfig.hasVisionApiKey &&
      sameHost(effectiveVisionBase.value, wardrobeStore.aiConfig.visionBaseUrl || wardrobeStore.aiConfig.baseUrl)
  )
);
const hasSavedImageKeyForCurrent = computed(() =>
  Boolean(
    wardrobeStore.aiConfig.hasImageApiKey &&
      sameHost(effectiveImageBase.value, wardrobeStore.aiConfig.imageBaseUrl || wardrobeStore.aiConfig.baseUrl)
  )
);

const hasDedicatedVisionKey = computed(() => Boolean(form.visionApiKey.trim() || hasSavedVisionKeyForCurrent.value));
const hasDedicatedImageKey = computed(() => Boolean(form.imageApiKey.trim() || hasSavedImageKeyForCurrent.value));

const visionInheritsMain = computed(() => !trimUrl(form.visionBaseUrl) || sameHost(form.visionBaseUrl, form.baseUrl));
const imageInheritsMain = computed(() => !trimUrl(form.imageBaseUrl) || sameHost(form.imageBaseUrl, form.baseUrl));

const visionKeySource = computed<'dedicated' | 'main' | 'image' | 'missing'>(() => {
  if (hasDedicatedVisionKey.value) return 'dedicated';
  if (visionInheritsMain.value && hasMainKeyEffective.value) return 'main';
  if (trimUrl(form.visionBaseUrl) && sameHost(form.visionBaseUrl, effectiveImageBase.value) && hasDedicatedImageKey.value) {
    return 'image';
  }
  return 'missing';
});

const imageKeySource = computed<'unsupported' | 'dedicated' | 'main' | 'vision' | 'missing'>(() => {
  if (isImageHostUnsupported.value) return 'unsupported';
  if (hasDedicatedImageKey.value) return 'dedicated';
  if (imageInheritsMain.value && hasMainKeyEffective.value) return 'main';
  if (trimUrl(form.imageBaseUrl) && sameHost(form.imageBaseUrl, effectiveVisionBase.value) && hasDedicatedVisionKey.value) {
    return 'vision';
  }
  return 'missing';
});

const vtonReadyEffective = computed(() => {
  if (!form.vtonEnabled) return false;
  const isMainDash = form.providerName === 'dashscope' || /dashscope\.aliyuncs\.com/i.test(form.baseUrl);
  return Boolean(form.vtonApiKey.trim() || wardrobeStore.aiConfig.hasVtonApiKey || (isMainDash && hasMainKeyEffective.value));
});

const anyChannelReady = computed(() => {
  const cfg = wardrobeStore.aiConfig;
  const vtonOk = Boolean(cfg.vtonEnabled && (cfg.hasVtonApiKey || cfg.vtonKeyReusesMain));
  return Boolean((cfg.enabled && (cfg.hasApiKey || cfg.visionReady || cfg.imageReady)) || vtonOk);
});

function applyPreset(preset: (typeof AI_PROVIDER_PRESETS)[number]) {
  form.providerName = preset.id;
  form.baseUrl = preset.baseUrl;
  form.chatModel = preset.chatModel;
  const hasIndependentVision = Boolean(trimUrl(form.visionBaseUrl) || wardrobeStore.aiConfig.hasVisionApiKey || form.visionApiKey.trim());
  if (!hasIndependentVision) {
    form.visionModel = preset.visionModel;
  }
  const hasIndependentImage = Boolean(trimUrl(form.imageBaseUrl) || wardrobeStore.aiConfig.hasImageApiKey || form.imageApiKey.trim());
  if (!hasIndependentImage) {
    form.imageModel = preset.imageModel || 'gpt-image-1';
  }
  testResult.value = null;
}

function fillCurrentOrigin() {
  if (typeof window !== 'undefined' && window.location?.origin) {
    form.vtonPublicBaseUrl = window.location.origin;
  }
}

function buildSavePayload(overrides: Record<string, unknown> = {}) {
  return {
    providerName: form.providerName,
    baseUrl: form.baseUrl,
    apiKey: form.apiKey.trim() || undefined,
    visionModel: form.visionModel,
    visionBaseUrl: form.visionBaseUrl.trim(),
    visionApiKey: form.visionApiKey.trim() || undefined,
    chatModel: form.chatModel,
    imageModel: form.imageModel.trim() || 'gpt-image-1',
    imageBaseUrl: form.imageBaseUrl.trim(),
    imageApiKey: form.imageApiKey.trim() || undefined,
    enabled: form.enabled,
    vtonEnabled: form.vtonEnabled,
    vtonProvider: 'dashscope',
    vtonModel: form.vtonModel,
    vtonApiKey: form.vtonApiKey.trim() || undefined,
    vtonPublicBaseUrl: form.vtonPublicBaseUrl.trim(),
    ...overrides,
  };
}

async function handleSave() {
  saving.value = true;
  testResult.value = null;
  try {
    await wardrobeStore.saveAiConfig(buildSavePayload());
    form.apiKey = '';
    form.visionApiKey = '';
    form.imageApiKey = '';
    form.vtonApiKey = '';
    toast.show('多通道模型路由与加密 API Key 已保存并在服务端生效');
  } catch (e: any) {
    toast.show(e.message || '保存配置失败');
  } finally {
    saving.value = false;
  }
}

async function handleClearKey() {
  saving.value = true;
  testResult.value = null;
  try {
    await wardrobeStore.saveAiConfig(buildSavePayload({ apiKey: undefined, clearApiKey: true }));
    form.apiKey = '';
    toast.show('已清除服务端保存的主站 API Key');
  } catch (e: any) {
    toast.show(e.message || '清除失败');
  } finally {
    saving.value = false;
  }
}

async function handleClearVisionKey() {
  saving.value = true;
  testResult.value = null;
  try {
    await wardrobeStore.saveAiConfig(buildSavePayload({ visionApiKey: undefined, clearVisionApiKey: true }));
    form.visionApiKey = '';
    toast.show('已清除独立视觉识别 API Key');
  } catch (e: any) {
    toast.show(e.message || '清除失败');
  } finally {
    saving.value = false;
  }
}

async function handleClearImageKey() {
  saving.value = true;
  testResult.value = null;
  try {
    await wardrobeStore.saveAiConfig(buildSavePayload({ imageApiKey: undefined, clearImageApiKey: true }));
    form.imageApiKey = '';
    toast.show('已清除独立生图 API Key');
  } catch (e: any) {
    toast.show(e.message || '清除失败');
  } finally {
    saving.value = false;
  }
}

async function handleClearVtonKey() {
  saving.value = true;
  try {
    await wardrobeStore.saveAiConfig(buildSavePayload({ vtonApiKey: undefined, clearVtonApiKey: true }));
    form.vtonApiKey = '';
    toast.show('已清除独立 VTON API Key');
  } catch (e: any) {
    toast.show(e.message || '清除失败');
  } finally {
    saving.value = false;
  }
}

async function handleTest() {
  testing.value = true;
  testResult.value = null;
  try {
    testResult.value = await aiConfigApi.test({
      channel: 'all',
      baseUrl: form.baseUrl,
      apiKey: form.apiKey.trim() || undefined,
      chatModel: form.chatModel,
      visionBaseUrl: form.visionBaseUrl.trim(),
      visionApiKey: form.visionApiKey.trim() || undefined,
      visionModel: form.visionModel,
      imageBaseUrl: form.imageBaseUrl.trim(),
      imageApiKey: form.imageApiKey.trim() || undefined,
      imageModel: form.imageModel.trim() || 'gpt-image-1',
      vtonEnabled: form.vtonEnabled,
      vtonApiKey: form.vtonApiKey.trim() || undefined,
      vtonModel: form.vtonModel,
    });
    toast.show(testResult.value.success ? '服务端已检通道全部连通成功' : testResult.value.message);
  } catch (e: any) {
    testResult.value = {
      success: false,
      latencyMs: 0,
      message: e.message || '连通性测试失败',
    };
  } finally {
    testing.value = false;
  }
}

async function handleUploadReference(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  uploadingRef.value = true;
  try {
    await wardrobeStore.uploadModelReference(file);
    toast.show('本人 / 模特参考照已上传，可在搭配画板中一键生成 AI 上身预览图');
  } catch (err: any) {
    toast.show(err.message || '参考照上传失败');
  } finally {
    uploadingRef.value = false;
    input.value = '';
  }
}

async function handleClearReference() {
  uploadingRef.value = true;
  try {
    await wardrobeStore.clearModelReference();
    toast.show('已清除本人 / 模特参考照');
  } catch (err: any) {
    toast.show(err.message || '清除失败');
  } finally {
    uploadingRef.value = false;
  }
}
</script>

<template>
  <div class="bg-white rounded-3xl border border-black/[0.08] shadow-editorial p-6 space-y-5">
    <div class="flex items-start justify-between gap-3">
      <div>
        <div class="flex items-center gap-2 flex-wrap">
          <h3 class="text-lg font-semibold text-black">多模态大模型 &amp; 生图 API 服务端配置</h3>
          <span
            class="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[11px] font-mono border"
            :class="
              anyChannelReady
                ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                : 'bg-[#F5F5F5] text-[#666666] border-black/10'
            "
          >
            <span
              class="w-1.5 h-1.5 rounded-full"
              :class="anyChannelReady ? 'bg-emerald-500' : 'bg-[#999999]'"
            />
            {{ anyChannelReady ? '服务端已激活' : '本地降级模式' }}
          </span>
        </div>
        <p class="text-[12px] text-[#666666] mt-1">
          支持任意 OpenAI 兼容端点与分通道路由：主站负责推理与默认识图，生图与视觉可按需独立指派中转站；所有 Key 经 AES-256-GCM 加密落库。
        </p>
      </div>
      <label class="inline-flex items-center gap-1.5 text-[12px] text-[#333333] cursor-pointer shrink-0 pt-0.5">
        <input v-model="form.enabled" type="checkbox" class="accent-black rounded" />
        启用实时模型
      </label>
    </div>

    <!-- Effective Routing Overview -->
    <div class="rounded-2xl bg-[#FAFAFA] border border-black/[0.08] p-3.5 space-y-2">
      <div class="flex items-center justify-between gap-2">
        <span class="text-[11.5px] font-semibold text-black">当前生效路由总览</span>
        <span class="text-[10.5px] text-[#777777]">不同站点之间 Key 严格隔离，绝不跨站透传</span>
      </div>
      <div class="grid grid-cols-1 sm:grid-cols-2 gap-2 text-[11px]">
        <div class="px-3 py-2 rounded-xl bg-white border border-black/[0.06] flex items-center justify-between gap-2 min-w-0">
          <div class="min-w-0">
            <div class="text-[#666666]">搭配推理（Stylist）</div>
            <div class="font-mono text-black truncate">{{ form.chatModel || '-' }} · {{ hostLabel(form.baseUrl) }}</div>
          </div>
          <span
            class="px-2 py-0.5 rounded-full text-[10px] font-mono shrink-0 border"
            :class="hasMainKeyEffective ? 'bg-emerald-50 text-emerald-700 border-emerald-200' : 'bg-[#F5F5F5] text-[#666666] border-black/10'"
          >
            {{ hasMainKeyEffective ? '主站' : '本地引擎' }}
          </span>
        </div>

        <div class="px-3 py-2 rounded-xl bg-white border border-black/[0.06] flex items-center justify-between gap-2 min-w-0">
          <div class="min-w-0">
            <div class="text-[#666666]">视觉识图（Vision）</div>
            <div class="font-mono text-black truncate">{{ form.visionModel || '-' }} · {{ hostLabel(effectiveVisionBase) }}</div>
          </div>
          <span
            class="px-2 py-0.5 rounded-full text-[10px] font-mono shrink-0 border"
            :class="
              visionKeySource === 'dedicated' || visionKeySource === 'image'
                ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                : visionKeySource === 'main'
                  ? 'bg-[#F5F5F5] text-black border-black/10'
                  : 'bg-amber-50 text-amber-700 border-amber-200'
            "
          >
            {{
              visionKeySource === 'dedicated'
                ? '独立覆盖'
                : visionKeySource === 'image'
                  ? '复用生图站'
                  : visionKeySource === 'main'
                    ? '复用主站'
                    : '缺少 Key'
            }}
          </span>
        </div>

        <div class="px-3 py-2 rounded-xl bg-white border border-black/[0.06] flex items-center justify-between gap-2 min-w-0">
          <div class="min-w-0">
            <div class="text-[#666666]">1:1 专业试衣（VTON）</div>
            <div class="font-mono text-black truncate">
              {{ form.vtonEnabled ? `${normalizeClientVtonModel(form.vtonModel)} · dashscope.aliyuncs.com` : '未启用（直接走下方生图）' }}
            </div>
          </div>
          <span
            class="px-2 py-0.5 rounded-full text-[10px] font-mono shrink-0 border"
            :class="
              vtonReadyEffective
                ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                : form.vtonEnabled
                  ? 'bg-amber-50 text-amber-700 border-amber-200'
                  : 'bg-[#F5F5F5] text-[#666666] border-black/10'
            "
          >
            {{ vtonReadyEffective ? '优先直连' : form.vtonEnabled ? '缺百炼 Key' : '未启用' }}
          </span>
        </div>

        <div class="px-3 py-2 rounded-xl bg-white border border-black/[0.06] flex items-center justify-between gap-2 min-w-0">
          <div class="min-w-0">
            <div class="text-[#666666]">通用 / 兜底生图（Image）</div>
            <div class="font-mono text-black truncate">{{ form.imageModel || 'gpt-image-1' }} · {{ hostLabel(effectiveImageBase) }}</div>
          </div>
          <span
            class="px-2 py-0.5 rounded-full text-[10px] font-mono shrink-0 border"
            :class="
              imageKeySource === 'dedicated' || imageKeySource === 'vision'
                ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                : imageKeySource === 'main'
                  ? 'bg-[#F5F5F5] text-black border-black/10'
                  : 'bg-amber-50 text-amber-700 border-amber-200'
            "
          >
            {{
              imageKeySource === 'dedicated'
                ? '独立中转'
                : imageKeySource === 'vision'
                  ? '复用视觉站'
                  : imageKeySource === 'main'
                    ? '复用主站'
                    : imageKeySource === 'unsupported'
                      ? '官方不支持生图'
                      : '缺少 Key'
            }}
          </span>
        </div>
      </div>
    </div>

    <!-- Provider Quick Presets -->
    <div>
      <div class="text-[11.5px] font-medium text-[#666666] mb-2">主站快捷预设（点击仅更新主站；已配置独立生图/视觉端点时不会覆盖）：</div>
      <div class="flex flex-wrap gap-1.5">
        <button
          v-for="p in AI_PROVIDER_PRESETS"
          :key="p.id"
          type="button"
          @click="applyPreset(p)"
          class="px-3 py-1 rounded-full text-[11.5px] font-medium border transition"
          :class="
            form.providerName === p.id
              ? 'bg-black text-white border-black'
              : 'bg-[#FAFAFA] text-[#333333] border-black/10 hover:bg-[#F2F2F2]'
          "
        >
          {{ p.label }}
        </button>
      </div>
    </div>

    <!-- Section 1: Main Channel (Stylist + Default Vision) -->
    <div class="space-y-3.5">
      <div>
        <label class="block text-[12px] font-medium text-[#333333] mb-1">主站 OpenAI 兼容 Base URL</label>
        <input
          v-model.trim="form.baseUrl"
          type="url"
          placeholder="https://your-relay-domain/v1"
          class="w-full px-3.5 py-2.5 rounded-2xl bg-[#FAFAFA] border border-black/10 focus:border-black focus:bg-white outline-none text-[13px] font-mono transition"
        />
      </div>

      <div
        v-if="isMainDeepSeek"
        class="px-3.5 py-2.5 rounded-2xl bg-amber-50/80 border border-amber-200 text-[11.5px] text-amber-900 leading-relaxed"
      >
        已选择 <strong>DeepSeek 官方</strong>：官方 <code class="font-mono">deepseek-chat</code> 负责搭配推理，<code class="font-mono">deepseek-flash</code> 支持 <code class="font-mono">image_url</code> 识图；官方不提供通用生图接口，请在下方「通用 / 兜底生图通道」填写中转站端点，或开启「阿里云百炼 VTON」。
      </div>

      <div>
        <div class="flex items-center justify-between mb-1">
          <label class="text-[12px] font-medium text-[#333333]">主站模型 API Key</label>
          <span v-if="wardrobeStore.aiConfig.hasApiKey" class="font-mono text-[11px] text-emerald-700">
            服务端已加密保存：{{ wardrobeStore.aiConfig.apiKeyMasked }}
          </span>
        </div>
        <div class="relative">
          <AppIcon name="key" :size="15" class="absolute left-3.5 top-1/2 -translate-y-1/2 text-[#666666]" />
          <input
            v-model="form.apiKey"
            :type="showKey ? 'text' : 'password'"
            :placeholder="
              wardrobeStore.aiConfig.hasApiKey
                ? '留空将保持已保存的加密 Key，输入新 Key 即覆盖更新'
                : '请输入 sk-...（保存后立即在后端生效）'
            "
            class="w-full pl-10 pr-10 py-2.5 rounded-2xl bg-[#FAFAFA] border border-black/10 focus:border-black focus:bg-white outline-none text-[13px] font-mono transition"
          />
          <button
            type="button"
            @click="showKey = !showKey"
            class="absolute right-3.5 top-1/2 -translate-y-1/2 text-[#666666] hover:text-black"
          >
            <AppIcon :name="showKey ? 'eye-off' : 'eye'" :size="15" />
          </button>
        </div>
      </div>

      <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <div>
          <label class="block text-[12px] font-medium text-[#333333] mb-1">搭配推理模型（Stylist）</label>
          <input
            v-model.trim="form.chatModel"
            type="text"
            placeholder="gpt-4o-mini / qwen-plus / deepseek-chat"
            class="w-full px-3 py-2 rounded-2xl bg-[#FAFAFA] border border-black/10 focus:border-black focus:bg-white outline-none text-[12px] font-mono"
          />
        </div>
        <div>
          <label class="block text-[12px] font-medium text-[#333333] mb-1">视觉识别模型（Vision）</label>
          <input
            v-model.trim="form.visionModel"
            type="text"
            placeholder="gpt-4o / qwen-vl-max / deepseek-flash"
            class="w-full px-3 py-2 rounded-2xl bg-[#FAFAFA] border border-black/10 focus:border-black focus:bg-white outline-none text-[12px] font-mono"
          />
        </div>
      </div>

      <!-- Optional Collapsible Vision Endpoint Override -->
      <div class="rounded-2xl border border-black/[0.08] bg-[#FAFAFA]/70 px-3.5 py-2.5">
        <button
          type="button"
          @click="showVisionOverride = !showVisionOverride"
          class="w-full flex items-center justify-between gap-2 text-left text-[12px] font-medium text-[#333333] hover:text-black"
        >
          <span class="flex items-center gap-1.5">
            <span>高级：单独指定视觉识别端点 / Key</span>
            <span class="text-[11px] font-normal text-[#777777]">（可选，留空默认复用主站）</span>
          </span>
          <AppIcon
            name="chevron-right"
            :size="14"
            class="transition-transform text-[#666666]"
            :class="showVisionOverride ? 'rotate-90' : ''"
          />
        </button>

        <div v-if="showVisionOverride" class="mt-3 pt-3 border-t border-black/[0.06] space-y-2.5">
          <p class="text-[11px] text-[#666666] leading-relaxed">
            适用于主站仅配纯文本模型，想把拍照识图、12 格拼图选品与试穿质检单独路由到另一套强视觉模型服务商。若与下方通用生图填写同一 Base URL，将自动同域复用生图 Key。
          </p>
          <div>
            <label class="block text-[11.5px] font-medium text-[#333333] mb-1">独立视觉 Base URL（留空复用主站）</label>
            <input
              v-model.trim="form.visionBaseUrl"
              type="url"
              placeholder="例如 https://your-vision-relay/v1（留空则复用主站 Base URL）"
              class="w-full px-3.5 py-2 rounded-xl bg-white border border-black/10 focus:border-black outline-none text-[12px] font-mono transition"
            />
          </div>
          <div>
            <div class="flex items-center justify-between mb-1">
              <label class="text-[11.5px] font-medium text-[#333333]">独立视觉 API Key（留空按同域规则复用）</label>
              <div class="flex items-center gap-2">
                <span v-if="wardrobeStore.aiConfig.hasVisionApiKey" class="font-mono text-[10.5px] text-emerald-700">
                  已保存：{{ wardrobeStore.aiConfig.visionApiKeyMasked }}
                </span>
                <span v-else-if="visionKeySource === 'main'" class="text-[10.5px] text-[#666666]">
                  当前复用主站 Key
                </span>
                <span v-else-if="visionKeySource === 'image'" class="text-[10.5px] text-emerald-700">
                  同域复用生图 Key
                </span>
                <button
                  v-if="wardrobeStore.aiConfig.hasVisionApiKey"
                  type="button"
                  @click="handleClearVisionKey"
                  :disabled="saving"
                  class="text-[10.5px] text-red-600 hover:underline"
                >
                  清除
                </button>
              </div>
            </div>
            <div class="relative">
              <input
                v-model="form.visionApiKey"
                :type="showVisionKey ? 'text' : 'password'"
                :placeholder="
                  wardrobeStore.aiConfig.hasVisionApiKey
                    ? '留空保持已保存视觉 Key，输入新 Key 覆盖'
                    : visionInheritsMain
                      ? '留空复用主站 Key；或输入独立视觉 Key'
                      : '当前填写了独立端点，请输入对应视觉 Key'
                "
                class="w-full pl-3.5 pr-9 py-2 rounded-xl bg-white border border-black/10 focus:border-black outline-none text-[12px] font-mono transition"
              />
              <button
                type="button"
                @click="showVisionKey = !showVisionKey"
                class="absolute right-3 top-1/2 -translate-y-1/2 text-[#666666] hover:text-black"
              >
                <AppIcon :name="showVisionKey ? 'eye-off' : 'eye'" :size="14" />
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- Section 2: Dedicated General Image Generation Channel -->
      <div class="p-4 rounded-2xl bg-[#FAFAFA] border border-black/[0.08] space-y-3">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <div class="flex items-center gap-2 flex-wrap">
            <span class="text-[13px] font-semibold text-black">通用 / 兜底生图通道（Image API）</span>
            <span
              class="px-2 py-0.5 rounded-full text-[10.5px] font-mono border"
              :class="
                imageKeySource === 'dedicated' || imageKeySource === 'vision'
                  ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                  : imageKeySource === 'main'
                    ? 'bg-white text-black border-black/10'
                    : 'bg-amber-50 text-amber-700 border-amber-200'
              "
            >
              {{
                imageKeySource === 'dedicated'
                  ? `独立中转已配置 · ${form.imageModel || 'gpt-image-1'}`
                  : imageKeySource === 'vision'
                    ? `同域复用视觉站 · ${form.imageModel || 'gpt-image-1'}`
                    : imageKeySource === 'main'
                      ? `复用主站 · ${form.imageModel || 'gpt-image-1'}`
                      : imageKeySource === 'unsupported'
                        ? '主站官方不支持生图 · 建议配中转站'
                        : '已填独立地址，请补充生图 Key'
              }}
            </span>
          </div>
          <span class="text-[11px] text-[#777777]">可单独配中转站，不填则继承主站</span>
        </div>

        <p class="text-[11.5px] text-[#666666] leading-relaxed">
          当下方百炼 VTON 未启用或触发兜底时，系统会走该通道调 <code class="font-mono">/v1/images/edits</code> 与 <code class="font-mono">/v1/images/generations</code>。如果你主站填了 DeepSeek 官方，只需在这里单独填中转站地址与 Key 即可实现混合路由。
        </p>

        <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div>
            <label class="block text-[11.5px] font-medium text-[#333333] mb-1">通用生图模型名（Try-On）</label>
            <input
              v-model.trim="form.imageModel"
              type="text"
              placeholder="gpt-image-1 / dall-e-3"
              class="w-full px-3.5 py-2 rounded-xl bg-white border border-black/10 focus:border-black outline-none text-[12px] font-mono transition"
            />
          </div>
          <div>
            <label class="block text-[11.5px] font-medium text-[#333333] mb-1">独立生图 Base URL（可选，留空复用主站）</label>
            <input
              v-model.trim="form.imageBaseUrl"
              type="url"
              placeholder="例如 https://your-relay-domain/v1"
              class="w-full px-3.5 py-2 rounded-xl bg-white border border-black/10 focus:border-black outline-none text-[12px] font-mono transition"
            />
          </div>
        </div>

        <div>
          <div class="flex items-center justify-between mb-1">
            <label class="text-[11.5px] font-medium text-[#333333]">独立生图 API Key（可选）</label>
            <div class="flex items-center gap-2">
              <span v-if="wardrobeStore.aiConfig.hasImageApiKey" class="font-mono text-[10.5px] text-emerald-700">
                已保存：{{ wardrobeStore.aiConfig.imageApiKeyMasked }}
              </span>
              <span v-else-if="imageKeySource === 'main'" class="text-[10.5px] text-[#666666]">
                当前复用主站 Key
              </span>
              <span v-else-if="imageKeySource === 'vision'" class="text-[10.5px] text-emerald-700">
                同域复用视觉 Key
              </span>
              <button
                v-if="wardrobeStore.aiConfig.hasImageApiKey"
                type="button"
                @click="handleClearImageKey"
                :disabled="saving"
                class="text-[10.5px] text-red-600 hover:underline"
              >
                清除
              </button>
            </div>
          </div>
          <div class="relative">
            <input
              v-model="form.imageApiKey"
              :type="showImageKey ? 'text' : 'password'"
              :placeholder="
                wardrobeStore.aiConfig.hasImageApiKey
                  ? '留空保持已保存生图 Key，输入新 Key 覆盖'
                  : imageInheritsMain
                    ? '留空自动复用主站 Key；也可填写独立中转站 sk-...'
                    : '已填写独立中转站 URL，请输入对应生图 sk-...'
              "
              class="w-full pl-3.5 pr-9 py-2 rounded-xl bg-white border border-black/10 focus:border-black outline-none text-[12px] font-mono transition"
            />
            <button
              type="button"
              @click="showImageKey = !showImageKey"
              class="absolute right-3 top-1/2 -translate-y-1/2 text-[#666666] hover:text-black"
            >
              <AppIcon :name="showImageKey ? 'eye-off' : 'eye'" :size="14" />
            </button>
          </div>
        </div>
      </div>

      <!-- Section 3: Dedicated Alibaba Bailian VTON Channel (Plan C) -->
      <div class="p-4 rounded-2xl bg-[#FAFAFA] border border-black/[0.08] space-y-3">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <div class="flex items-center gap-2 flex-wrap">
            <span class="text-[13px] font-semibold text-black">专业虚拟试衣通道 · 阿里云百炼多图融合（VTON）</span>
            <span
              class="px-2 py-0.5 rounded-full text-[10.5px] font-mono border"
              :class="
                wardrobeStore.aiConfig.vtonEnabled &&
                (wardrobeStore.aiConfig.hasVtonApiKey || wardrobeStore.aiConfig.vtonKeyReusesMain)
                  ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                  : 'bg-white text-[#666666] border-black/10'
              "
            >
              {{
                wardrobeStore.aiConfig.vtonEnabled &&
                (wardrobeStore.aiConfig.hasVtonApiKey || wardrobeStore.aiConfig.vtonKeyReusesMain)
                  ? `百炼多图试穿已激活 · ${normalizeClientVtonModel(wardrobeStore.aiConfig.vtonModel)}`
                  : '未启用 · 使用通用生图通道'
              }}
            </span>
          </div>
          <label class="inline-flex items-center gap-1.5 text-[12px] font-medium text-black cursor-pointer">
            <input v-model="form.vtonEnabled" type="checkbox" class="accent-black rounded" />
            启用 1:1 多图虚拟试穿
          </label>
        </div>

        <p class="text-[11.5px] text-[#666666] leading-relaxed">
          最高优先级通道：使用阿里云百炼新一代多图融合模型（默认 <code class="font-mono">qwen-image-2.1-pro</code>），在服务端自动将模特图与最多 5 件单品抠图转为白底 JPEG Base64 直传合成，<strong>本地与公网环境均可直接使用</strong>；若配额或网络异常，自动无缝回退至上方「通用 / 兜底生图通道」。
        </p>

        <div class="grid grid-cols-1 gap-3">
          <div>
            <label class="block text-[11.5px] font-medium text-[#333333] mb-1">百炼多图试穿模型</label>
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-1.5">
              <button
                type="button"
                @click="form.vtonModel = 'qwen-image-2.1-pro'"
                class="px-3 py-2 rounded-xl border text-[11.5px] font-medium text-left transition"
                :class="
                  form.vtonModel === 'qwen-image-2.1-pro'
                    ? 'bg-black text-white border-black'
                    : 'bg-white text-[#333333] border-black/10 hover:border-black/30'
                "
              >
                <div class="font-mono">qwen-image-2.1-pro（推荐）</div>
                <div class="text-[10px] opacity-80">全套最多5件融合 · 免费额度通常默认可用</div>
              </button>
              <button
                type="button"
                @click="form.vtonModel = 'qwen-image-2.1-turbo'"
                class="px-3 py-2 rounded-xl border text-[11.5px] font-medium text-left transition"
                :class="
                  form.vtonModel === 'qwen-image-2.1-turbo'
                    ? 'bg-black text-white border-black'
                    : 'bg-white text-[#333333] border-black/10 hover:border-black/30'
                "
              >
                <div class="font-mono">qwen-image-2.1-turbo（极速）</div>
                <div class="text-[10px] opacity-80">全套最多5件融合 · 生成速度更快</div>
              </button>
              <button
                type="button"
                @click="form.vtonModel = 'qwen-image-3.0-pro'"
                class="px-3 py-2 rounded-xl border text-[11.5px] font-medium text-left transition"
                :class="
                  form.vtonModel === 'qwen-image-3.0-pro'
                    ? 'bg-black text-white border-black'
                    : 'bg-white text-[#333333] border-black/10 hover:border-black/30'
                "
              >
                <div class="font-mono">qwen-image-3.0-pro（精选2件）</div>
                <div class="text-[10px] opacity-80">专注上/下装核心单品精细贴合</div>
              </button>
              <button
                type="button"
                @click="form.vtonModel = 'wan2.7-image-pro'"
                class="px-3 py-2 rounded-xl border text-[11.5px] font-medium text-left transition"
                :class="
                  form.vtonModel === 'wan2.7-image-pro'
                    ? 'bg-black text-white border-black'
                    : 'bg-white text-[#333333] border-black/10 hover:border-black/30'
                "
              >
                <div class="font-mono">wan2.7-image-pro（万相）</div>
                <div class="text-[10px] opacity-80">写实光影与全套最多5件生图强</div>
              </button>
            </div>
          </div>

          <div>
            <div class="flex items-center justify-between mb-1">
              <label class="text-[11.5px] font-medium text-[#333333]">百炼 VTON API Key</label>
              <div class="flex items-center gap-2">
                <span v-if="wardrobeStore.aiConfig.hasVtonApiKey" class="font-mono text-[10.5px] text-emerald-700">
                  已保存：{{ wardrobeStore.aiConfig.vtonApiKeyMasked }}
                </span>
                <span v-else-if="wardrobeStore.aiConfig.vtonKeyReusesMain" class="text-[10.5px] text-emerald-700">
                  自动复用上方百炼主 Key
                </span>
                <button
                  v-if="wardrobeStore.aiConfig.hasVtonApiKey"
                  type="button"
                  @click="handleClearVtonKey"
                  :disabled="saving"
                  class="text-[10.5px] text-red-600 hover:underline"
                >
                  清除
                </button>
              </div>
            </div>
            <div class="relative">
              <input
                v-model="form.vtonApiKey"
                :type="showVtonKey ? 'text' : 'password'"
                :placeholder="
                  wardrobeStore.aiConfig.hasVtonApiKey
                    ? '留空保留已保存 Key，输入新 Key 覆盖'
                    : wardrobeStore.aiConfig.vtonKeyReusesMain
                      ? '留空即自动复用上方主百炼 Key'
                      : '输入阿里云百炼 sk-...（不填则无法调用 VTON）'
                "
                class="w-full pl-3.5 pr-9 py-2 rounded-xl bg-white border border-black/10 focus:border-black outline-none text-[12px] font-mono transition"
              />
              <button
                type="button"
                @click="showVtonKey = !showVtonKey"
                class="absolute right-3 top-1/2 -translate-y-1/2 text-[#666666] hover:text-black"
              >
                <AppIcon :name="showVtonKey ? 'eye-off' : 'eye'" :size="14" />
              </button>
            </div>
          </div>
        </div>

        <div>
          <div class="flex items-center justify-between gap-2 mb-1">
            <label class="text-[11.5px] font-medium text-[#333333]">
              可选静态资产回源地址
              <span class="font-normal text-[#666666]">（已升级 Base64 直传，通常留空即可）</span>
            </label>
            <button
              type="button"
              @click="fillCurrentOrigin"
              class="text-[11px] text-black hover:underline font-medium"
            >
              填入当前访问地址
            </button>
          </div>
          <input
            v-model.trim="form.vtonPublicBaseUrl"
            type="url"
            placeholder="例如 http://47.111.163.151:8680（仅跨容器读取静态模特/单品图时备用）"
            class="w-full px-3.5 py-2 rounded-xl bg-white border border-black/10 focus:border-black outline-none text-[12px] font-mono transition"
          />
          <p class="text-[10.5px] text-[#777777] mt-1">
            服务端会自动把所有用户抠图与模特照压缩为白底 JPEG Base64 直传阿里云百炼，<code class="font-mono">localhost / 127.0.0.1</code> 本地开发同样可直接体验 1:1 实穿效果。
          </p>
        </div>
      </div>

      <!-- Model Reference Photo Upload for AI Editorial Try-On -->
      <div class="p-3.5 rounded-2xl bg-[#FAFAFA] border border-black/[0.08] flex flex-wrap items-center justify-between gap-3">
        <div class="flex items-center gap-3 min-w-0">
          <div class="w-12 h-14 rounded-xl bg-white border border-black/10 overflow-hidden flex items-center justify-center shrink-0">
            <img
              v-if="wardrobeStore.aiConfig.modelReferenceUrl"
              :src="wardrobeStore.aiConfig.modelReferenceUrl"
              alt="本人参考照"
              class="w-full h-full object-cover"
            />
            <AppIcon v-else name="user" :size="18" class="text-[#999999]" />
          </div>
          <div class="min-w-0">
            <div class="text-[12.5px] font-semibold text-black">AI 上身预览 · 本人/模特参考照</div>
            <p class="text-[11px] text-[#666666] mt-0.5 leading-snug">
              上传正面全身照后，搭配画板生成试穿大片时将优先保持本人形象一致。
            </p>
          </div>
        </div>
        <div class="flex items-center gap-2 shrink-0">
          <input
            ref="refFileInput"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            class="hidden"
            @change="handleUploadReference"
          />
          <button
            type="button"
            @click="refFileInput?.click()"
            :disabled="uploadingRef"
            class="px-3.5 py-1.5 rounded-full bg-white hover:bg-black hover:text-white border border-black/15 text-[12px] font-medium text-black transition"
          >
            {{ uploadingRef ? '处理中...' : wardrobeStore.aiConfig.modelReferenceUrl ? '更换照片' : '上传参考照' }}
          </button>
          <button
            v-if="wardrobeStore.aiConfig.modelReferenceUrl"
            type="button"
            @click="handleClearReference"
            :disabled="uploadingRef"
            class="px-2.5 py-1.5 rounded-full text-[11.5px] text-red-600 hover:bg-red-50 transition"
          >
            清除
          </button>
        </div>
      </div>
    </div>

    <!-- Connectivity Feedback -->
    <div v-if="testResult" class="space-y-2">
      <div
        class="px-4 py-3 rounded-2xl border text-[12px] flex items-center justify-between gap-2"
        :class="
          testResult.success
            ? 'bg-emerald-50 border-emerald-200 text-emerald-800'
            : 'bg-red-50 border-red-200 text-red-700'
        "
      >
        <span>{{ testResult.message }}</span>
        <span v-if="testResult.latencyMs" class="font-mono shrink-0">{{ testResult.latencyMs }}ms</span>
      </div>

      <div v-if="testResult.channels && testResult.channels.length" class="grid grid-cols-1 sm:grid-cols-2 gap-2">
        <div
          v-for="ch in testResult.channels"
          :key="ch.channel"
          class="px-3 py-2 rounded-xl border text-[11px] space-y-0.5"
          :class="
            ch.skipped
              ? 'bg-[#FAFAFA] border-black/[0.08] text-[#666666]'
              : ch.success
                ? 'bg-emerald-50/60 border-emerald-200 text-emerald-900'
                : 'bg-red-50/70 border-red-200 text-red-800'
          "
        >
          <div class="flex items-center justify-between gap-2 font-medium">
            <span>{{ ch.skipped ? '○' : ch.success ? '✓' : '✗' }} {{ ch.label }} · <span class="font-mono">{{ ch.model || '-' }}</span></span>
            <span v-if="ch.latencyMs" class="font-mono text-[10px] opacity-80">{{ ch.latencyMs }}ms</span>
          </div>
          <div class="text-[10.5px] opacity-80 font-mono truncate">{{ ch.endpoint }}</div>
          <div class="text-[10.5px] leading-snug">{{ ch.message }}</div>
        </div>
      </div>
    </div>

    <!-- Actions -->
    <div class="flex flex-wrap items-center justify-between gap-2.5 pt-2 border-t border-black/[0.08]">
      <div class="flex items-center gap-2">
        <button
          type="button"
          @click="handleTest"
          :disabled="testing"
          class="inline-flex items-center gap-1.5 px-4 py-2 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] border border-black/10 text-black text-[12.5px] font-medium transition disabled:opacity-60"
        >
          <AppIcon name="zap" :size="14" />
          {{ testing ? '分通道连通测试中...' : '测试各通道连通性' }}
        </button>
        <button
          v-if="wardrobeStore.aiConfig.hasApiKey"
          type="button"
          @click="handleClearKey"
          :disabled="saving"
          class="px-3 py-2 rounded-full text-[12px] text-red-600 hover:bg-red-50 transition"
        >
          清除主 Key
        </button>
      </div>
      <button
        type="button"
        @click="handleSave"
        :disabled="saving"
        class="inline-flex items-center gap-1.5 px-5 py-2 rounded-full bg-black hover:bg-[#262626] text-white text-[12.5px] font-medium shadow-sm transition disabled:opacity-60"
      >
        <AppIcon name="shield-check" :size="14" />
        {{ saving ? '保存中...' : '保存并在服务端生效' }}
      </button>
    </div>
  </div>
</template>
