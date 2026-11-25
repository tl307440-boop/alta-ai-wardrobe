<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
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
  chatModel: 'qwen-plus',
  imageModel: 'gpt-image-1',
  enabled: true,
});

const showKey = ref(false);
const saving = ref(false);
const testing = ref(false);
const uploadingRef = ref(false);
const refFileInput = ref<HTMLInputElement | null>(null);
const testResult = ref<AiTestResult | null>(null);

watch(
  () => wardrobeStore.aiConfig,
  (cfg) => {
    if (!cfg) return;
    form.providerName = cfg.providerName || 'dashscope';
    form.baseUrl = cfg.baseUrl || 'https://dashscope.aliyuncs.com/compatible-mode/v1';
    form.visionModel = cfg.visionModel || 'qwen-vl-max';
    form.chatModel = cfg.chatModel || 'qwen-plus';
    form.imageModel = cfg.imageModel || 'gpt-image-1';
    form.enabled = cfg.enabled;
  },
  { immediate: true }
);

function applyPreset(preset: (typeof AI_PROVIDER_PRESETS)[number]) {
  form.providerName = preset.id;
  form.baseUrl = preset.baseUrl;
  form.visionModel = preset.visionModel;
  form.chatModel = preset.chatModel;
  form.imageModel = preset.imageModel || 'gpt-image-1';
  testResult.value = null;
}

async function handleSave() {
  saving.value = true;
  testResult.value = null;
  try {
    await wardrobeStore.saveAiConfig({
      providerName: form.providerName,
      baseUrl: form.baseUrl,
      apiKey: form.apiKey.trim() || undefined,
      visionModel: form.visionModel,
      chatModel: form.chatModel,
      imageModel: form.imageModel.trim() || 'gpt-image-1',
      enabled: form.enabled,
    });
    form.apiKey = '';
    toast.show('模型 API Key 与生图模型已加密写入服务器并即时生效');
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
    await wardrobeStore.saveAiConfig({
      providerName: form.providerName,
      baseUrl: form.baseUrl,
      clearApiKey: true,
      visionModel: form.visionModel,
      chatModel: form.chatModel,
      imageModel: form.imageModel.trim() || 'gpt-image-1',
      enabled: form.enabled,
    });
    form.apiKey = '';
    toast.show('已清除服务端保存的 API Key，系统将使用本地智能降级引擎');
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
      baseUrl: form.baseUrl,
      apiKey: form.apiKey.trim() || undefined,
      chatModel: form.chatModel,
    });
    toast.show(testResult.value.success ? '服务端模型连通性测试通过' : testResult.value.message);
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
              wardrobeStore.aiConfig.hasApiKey && wardrobeStore.aiConfig.enabled
                ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                : 'bg-[#F5F5F5] text-[#666666] border-black/10'
            "
          >
            <span
              class="w-1.5 h-1.5 rounded-full"
              :class="wardrobeStore.aiConfig.hasApiKey && wardrobeStore.aiConfig.enabled ? 'bg-emerald-500' : 'bg-[#999999]'"
            />
            {{
              wardrobeStore.aiConfig.hasApiKey && wardrobeStore.aiConfig.enabled
                ? '服务端已激活'
                : '本地降级模式'
            }}
          </span>
        </div>
        <p class="text-[12px] text-[#666666] mt-1">
          支持任意 OpenAI 兼容中转站（New API / One API / Miau API）及官方端点；Key 经 AES-256-GCM 加密落库，绝不明文回传浏览器。
        </p>
      </div>
      <label class="inline-flex items-center gap-1.5 text-[12px] text-[#333333] cursor-pointer shrink-0 pt-0.5">
        <input v-model="form.enabled" type="checkbox" class="accent-black rounded" />
        启用实时模型
      </label>
    </div>

    <!-- Provider Quick Presets -->
    <div>
      <div class="text-[11.5px] font-medium text-[#666666] mb-2">快捷服务商预设（点击自动填充端点与模型名）：</div>
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

    <!-- Fields -->
    <div class="space-y-3.5">
      <div>
        <label class="block text-[12px] font-medium text-[#333333] mb-1">OpenAI 兼容 Base URL</label>
        <input
          v-model.trim="form.baseUrl"
          type="url"
          placeholder="https://your-relay-domain/v1"
          class="w-full px-3.5 py-2.5 rounded-2xl bg-[#FAFAFA] border border-black/10 focus:border-black focus:bg-white outline-none text-[13px] font-mono transition"
        />
      </div>

      <div>
        <div class="flex items-center justify-between mb-1">
          <label class="text-[12px] font-medium text-[#333333]">模型 API Key</label>
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

      <div class="grid grid-cols-1 sm:grid-cols-3 gap-3">
        <div>
          <label class="block text-[12px] font-medium text-[#333333] mb-1">视觉识别（Vision）</label>
          <input
            v-model.trim="form.visionModel"
            type="text"
            placeholder="gpt-4o / qwen-vl-max"
            class="w-full px-3 py-2 rounded-2xl bg-[#FAFAFA] border border-black/10 focus:border-black focus:bg-white outline-none text-[12px] font-mono"
          />
        </div>
        <div>
          <label class="block text-[12px] font-medium text-[#333333] mb-1">搭配推理（Stylist）</label>
          <input
            v-model.trim="form.chatModel"
            type="text"
            placeholder="gpt-4o-mini / qwen-plus"
            class="w-full px-3 py-2 rounded-2xl bg-[#FAFAFA] border border-black/10 focus:border-black focus:bg-white outline-none text-[12px] font-mono"
          />
        </div>
        <div>
          <label class="block text-[12px] font-medium text-[#333333] mb-1">上身生图（Try-On）</label>
          <input
            v-model.trim="form.imageModel"
            type="text"
            placeholder="gpt-image-1 / dall-e-3"
            class="w-full px-3 py-2 rounded-2xl bg-[#FAFAFA] border border-black/10 focus:border-black focus:bg-white outline-none text-[12px] font-mono"
          />
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
              上传正面全身照后，搭配画板生成试穿大片时将优先走 <code class="font-mono">/v1/images/edits</code> 保持形象一致。
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
    <div
      v-if="testResult"
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
          {{ testing ? '测试连通中...' : '测试服务端连通性' }}
        </button>
        <button
          v-if="wardrobeStore.aiConfig.hasApiKey"
          type="button"
          @click="handleClearKey"
          :disabled="saving"
          class="px-3 py-2 rounded-full text-[12px] text-red-600 hover:bg-red-50 transition"
        >
          清除 Key
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
