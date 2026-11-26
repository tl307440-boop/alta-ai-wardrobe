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
  vtonEnabled: false,
  vtonProvider: 'dashscope',
  vtonModel: 'aitryon-plus',
  vtonApiKey: '',
  vtonPublicBaseUrl: '',
});

const showKey = ref(false);
const showVtonKey = ref(false);
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
    form.vtonEnabled = Boolean(cfg.vtonEnabled);
    form.vtonProvider = cfg.vtonProvider || 'dashscope';
    form.vtonModel = cfg.vtonModel === 'aitryon' ? 'aitryon' : 'aitryon-plus';
    form.vtonPublicBaseUrl = cfg.vtonPublicBaseUrl || '';
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

function fillCurrentOrigin() {
  if (typeof window !== 'undefined' && window.location?.origin) {
    form.vtonPublicBaseUrl = window.location.origin;
  }
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
      vtonEnabled: form.vtonEnabled,
      vtonProvider: 'dashscope',
      vtonModel: form.vtonModel,
      vtonApiKey: form.vtonApiKey.trim() || undefined,
      vtonPublicBaseUrl: form.vtonPublicBaseUrl.trim(),
    });
    form.apiKey = '';
    form.vtonApiKey = '';
    toast.show('模型 API Key 与虚拟试衣配置已加密写入服务器并即时生效');
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
      vtonEnabled: form.vtonEnabled,
      vtonProvider: 'dashscope',
      vtonModel: form.vtonModel,
      vtonPublicBaseUrl: form.vtonPublicBaseUrl.trim(),
    });
    form.apiKey = '';
    toast.show('已清除服务端保存的主 API Key，系统将使用本地智能降级引擎');
  } catch (e: any) {
    toast.show(e.message || '清除失败');
  } finally {
    saving.value = false;
  }
}

async function handleClearVtonKey() {
  saving.value = true;
  try {
    await wardrobeStore.saveAiConfig({
      providerName: form.providerName,
      baseUrl: form.baseUrl,
      visionModel: form.visionModel,
      chatModel: form.chatModel,
      imageModel: form.imageModel.trim() || 'gpt-image-1',
      enabled: form.enabled,
      vtonEnabled: form.vtonEnabled,
      vtonProvider: 'dashscope',
      vtonModel: form.vtonModel,
      clearVtonApiKey: true,
      vtonPublicBaseUrl: form.vtonPublicBaseUrl.trim(),
    });
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

      <!-- Dedicated Alibaba Bailian VTON Channel (Plan C) -->
      <div class="p-4 rounded-2xl bg-[#FAFAFA] border border-black/[0.08] space-y-3">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <div class="flex items-center gap-2 flex-wrap">
            <span class="text-[13px] font-semibold text-black">专业虚拟试衣通道 · 阿里云百炼 AI 试衣（VTON）</span>
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
                  ? `百炼 VTON 已激活 · ${wardrobeStore.aiConfig.vtonModel || 'aitryon-plus'}`
                  : '未启用 · 使用通用生图'
              }}
            </span>
          </div>
          <label class="inline-flex items-center gap-1.5 text-[12px] font-medium text-black cursor-pointer">
            <input v-model="form.vtonEnabled" type="checkbox" class="accent-black rounded" />
            启用百炼 AI 试衣（1:1 真实上身）
          </label>
        </div>

        <p class="text-[11.5px] text-[#666666] leading-relaxed">
          使用阿里云百炼 <code class="font-mono">aitryon</code> / <code class="font-mono">aitryon-plus</code> 将衣橱真实单品像素级贴合到模特身上，不消耗本机算力；若接口失败或本地未映射公网地址，系统将自动无缝回退至上方通用生图模型。
        </p>

        <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div>
            <label class="block text-[11.5px] font-medium text-[#333333] mb-1">试衣模型版本</label>
            <div class="grid grid-cols-2 gap-1.5">
              <button
                type="button"
                @click="form.vtonModel = 'aitryon-plus'"
                class="px-3 py-2 rounded-xl border text-[11.5px] font-medium text-left transition"
                :class="
                  form.vtonModel === 'aitryon-plus'
                    ? 'bg-black text-white border-black'
                    : 'bg-white text-[#333333] border-black/10 hover:border-black/30'
                "
              >
                <div class="font-mono">aitryon-plus</div>
                <div class="text-[10px] opacity-80">推荐 · 高清版</div>
              </button>
              <button
                type="button"
                @click="form.vtonModel = 'aitryon'"
                class="px-3 py-2 rounded-xl border text-[11.5px] font-medium text-left transition"
                :class="
                  form.vtonModel === 'aitryon'
                    ? 'bg-black text-white border-black'
                    : 'bg-white text-[#333333] border-black/10 hover:border-black/30'
                "
              >
                <div class="font-mono">aitryon</div>
                <div class="text-[10px] opacity-80">极速基础版</div>
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
            <label class="text-[11.5px] font-medium text-[#333333]">公网访问基地址（Public Base URL）</label>
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
            placeholder="例如 http://47.111.163.151:8680（留空则自动读取当前公网域名/IP）"
            class="w-full px-3.5 py-2 rounded-xl bg-white border border-black/10 focus:border-black outline-none text-[12px] font-mono transition"
          />
          <p class="text-[10.5px] text-[#777777] mt-1">
            百炼试衣需要从公网 HTTP/HTTPS 地址拉取模特照与衣物图；本地 <code class="font-mono">localhost / 127.0.0.1</code> 测试环境无法被阿里云访问时，系统将自动平滑回退到通用生图通道。
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
