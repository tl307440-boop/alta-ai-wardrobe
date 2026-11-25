<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { useToastStore } from '@/stores/toast';
import { CITY_OPTIONS } from '@/utils/constants';
import AppIcon from '@/components/AppIcon.vue';
import GarmentSvg from '@/components/GarmentSvg.vue';

const router = useRouter();
const authStore = useAuthStore();
const toast = useToastStore();

const authMode = ref<'login' | 'register'>('login');
const authError = ref('');
const showPassword = ref(false);

const loginForm = reactive({
  username: '',
  password: '',
  remember: true,
});

const registerForm = reactive({
  username: '',
  nickname: '',
  password: '',
  confirmPassword: '',
  defaultCity: '武汉',
  loadStarterPack: true,
});

function fillDemoCredentials() {
  authMode.value = 'login';
  loginForm.username = 'demo_user';
  loginForm.password = 'Wardrobe2026!';
  authError.value = '';
}

async function quickDemoLogin() {
  fillDemoCredentials();
  await handleLogin();
}

async function handleLogin() {
  authError.value = '';
  if (!loginForm.username.trim() || !loginForm.password) {
    authError.value = '请输入用户名和密码';
    return;
  }
  try {
    const res = await authStore.login(loginForm.username.trim(), loginForm.password, loginForm.remember);
    toast.show(`欢迎回来，${res.userInfo.nickname}！JWT 鉴权已生效`);
    router.push('/');
  } catch (e: any) {
    authError.value = e.message || '登录失败，请检查账号密码';
  }
}

async function handleRegister() {
  authError.value = '';
  if (!registerForm.username.trim() || !registerForm.nickname.trim()) {
    authError.value = '请完整填写用户名与昵称';
    return;
  }
  if (registerForm.username.trim().length < 3) {
    authError.value = '用户名至少需要 3 位字符';
    return;
  }
  if (registerForm.password.length < 6) {
    authError.value = '密码长度至少 6 位字符';
    return;
  }
  if (registerForm.password !== registerForm.confirmPassword) {
    authError.value = '两次输入的密码不一致';
    return;
  }
  try {
    const res = await authStore.register({
      username: registerForm.username.trim(),
      nickname: registerForm.nickname.trim(),
      password: registerForm.password,
      defaultCity: registerForm.defaultCity,
      loadStarterPack: registerForm.loadStarterPack,
    });
    toast.show(
      `账号 @${res.userInfo.username} 注册成功，已为你初始化独立数字衣橱`
    );
    router.push('/');
  } catch (e: any) {
    authError.value = e.message || '注册失败';
  }
}
</script>

<template>
  <div class="min-h-screen flex flex-col lg:flex-row bg-oat-100">
    <!-- 左侧：杂志画廊品牌视觉展区 (54%) -->
    <div
      class="lg:w-[54%] flatlay-canvas border-b lg:border-b-0 lg:border-r border-oat-300 p-8 sm:p-12 lg:p-16 flex flex-col justify-between relative overflow-hidden"
    >
      <!-- Brand Header -->
      <div class="flex items-center justify-between z-10">
        <div class="flex items-center gap-3">
          <div class="w-10 h-10 rounded-full bg-black text-white flex items-center justify-center shadow-sm font-semibold text-sm">
            A
          </div>
          <div>
            <h1 class="text-xl font-semibold tracking-tight text-black leading-none">
              ALTA · 智搭衣橱
            </h1>
            <p class="text-[11.5px] text-warmgray mt-1">基于多模态大模型与个性化推荐的智能衣橱系统</p>
          </div>
        </div>
        <span
          class="hidden sm:inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-white/85 border border-oat-300 font-mono text-[11px] text-warmgray"
        >
          <span class="w-1.5 h-1.5 rounded-full bg-emerald-500" />
          JWT Auth Ready
        </span>
      </div>

      <!-- Center Editorial Headline + Floating Flat-Lay Collage -->
      <div class="my-10 lg:my-6 z-10 grid grid-cols-1 xl:grid-cols-12 gap-8 items-center">
        <div class="xl:col-span-6 space-y-5">
          <span
            class="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-terracotta-light text-terracotta-dark text-[12px] font-medium"
          >
            <AppIcon name="shield-check" :size="14" />
            私人数字衣橱 · 多用户隔离
          </span>
          <h2 class="font-serif text-4xl sm:text-[46px] font-semibold leading-[1.15] text-charcoal tracking-tight">
            把灵感，<br />穿进你的衣橱
          </h2>
          <p class="text-[14px] text-warmgray leading-relaxed">
            上传衣物实拍或穿搭街拍，由多模态大模型自动抠图与结构化打标；结合实时天气、出行场景与个人风格偏好，生成优先复用已有衣物的全身方案。
          </p>
          <div class="flex flex-wrap gap-2 pt-1">
            <span class="px-3 py-1.5 rounded-full bg-white/90 border border-oat-300 text-[12px] text-charcoal-soft">
              ✨ 5 步抠图与人工纠偏
            </span>
            <span class="px-3 py-1.5 rounded-full bg-white/90 border border-oat-300 text-[12px] text-charcoal-soft">
              🧥 街拍拆解与平替导购
            </span>
            <span class="px-3 py-1.5 rounded-full bg-white/90 border border-oat-300 text-[12px] text-charcoal-soft">
              📅 天气穿搭日历闭环
            </span>
          </div>
        </div>

        <!-- Floating Garment Lookbook Cards -->
        <div class="xl:col-span-6 flex items-center justify-center">
          <div class="relative w-full max-w-[310px] h-[290px]">
            <div
              class="absolute left-0 top-2 w-36 h-44 bg-white/95 rounded-3xl border border-oat-300 shadow-editorial p-3 -rotate-3 transition hover:rotate-0 hover:scale-105 duration-300"
            >
              <GarmentSvg type="trench" color="#DFD3C3" class="w-full h-full" />
              <span
                class="absolute -top-2.5 -left-2 text-[10.5px] font-mono bg-charcoal text-white px-2.5 py-0.5 rounded-full shadow-sm"
              >
                外套 · 燕麦
              </span>
            </div>
            <div
              class="absolute right-0 top-8 w-32 h-40 bg-white/95 rounded-3xl border border-oat-300 shadow-editorial p-3 rotate-3 transition hover:rotate-0 hover:scale-105 duration-300"
            >
              <GarmentSvg type="jeans" color="#3B5372" class="w-full h-full" />
              <span class="absolute -top-2 right-2 text-[10px] bg-emerald-600 text-white px-2 py-0.5 rounded-full">
                衣橱已有
              </span>
            </div>
            <div
              class="absolute left-6 bottom-0 w-44 h-28 bg-white rounded-3xl border border-oat-300 shadow-float p-2.5 -rotate-1 transition hover:scale-105 duration-300 flex items-center gap-2"
            >
              <div class="w-16 h-16 shrink-0">
                <GarmentSvg type="loafers" color="#8A5230" class="w-full h-full" />
              </div>
              <div class="text-left pr-1 min-w-0">
                <div class="text-[11px] font-semibold text-charcoal whitespace-nowrap">焦糖乐福鞋</div>
                <div class="font-mono text-[10px] text-terracotta mt-0.5 whitespace-nowrap">匹配度 96%</div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Bottom Tech Stack Footnote -->
      <div
        class="flex flex-wrap items-center justify-between gap-2 pt-4 border-t border-oat-300/80 text-[12px] text-warmgray z-10"
      >
        <span class="font-mono">Spring Boot 3 + Vue 3 + MySQL 8 + JWT</span>
        <span>ALTA · 极简纯白画廊 · 中国本土灵感电商闭环</span>
      </div>
    </div>

    <!-- 右侧：登录 / 注册白卡表单 (46%) -->
    <div class="lg:w-[46%] flex items-center justify-center p-6 sm:p-10 lg:p-14 bg-white">
      <div class="w-full max-w-md bg-white rounded-3xl border border-oat-300 shadow-editorial p-7 sm:p-9 space-y-6">
        <!-- Segmented Tab -->
        <div class="flex p-1.5 rounded-2xl bg-oat-100 border border-oat-300/80">
          <button
            type="button"
            @click="authMode = 'login'; authError = ''"
            class="flex-1 py-2.5 rounded-xl text-[13.5px] font-semibold transition"
            :class="authMode === 'login' ? 'bg-white text-charcoal shadow-sm' : 'text-warmgray hover:text-charcoal'"
          >
            账号登录
          </button>
          <button
            type="button"
            @click="authMode = 'register'; authError = ''"
            class="flex-1 py-2.5 rounded-xl text-[13.5px] font-semibold transition"
            :class="authMode === 'register' ? 'bg-white text-charcoal shadow-sm' : 'text-warmgray hover:text-charcoal'"
          >
            注册新账号
          </button>
        </div>

        <!-- Header -->
        <div>
          <h3 class="font-serif text-2xl font-semibold text-charcoal">
            {{ authMode === 'login' ? '欢迎回到你的衣橱' : '创建私人数字衣橱' }}
          </h3>
          <p class="text-[12.5px] text-warmgray mt-1">
            {{
              authMode === 'login'
                ? '使用账号密码登录，或点击下方「演示账号一键体验」快速进入'
                : '每个账号拥有完全独立的衣橱单品、模型 Key 配置与穿搭日历数据'
            }}
          </p>
        </div>

        <!-- Validation Error -->
        <div
          v-if="authError"
          class="px-4 py-3 rounded-2xl bg-red-50 border border-red-200 text-red-700 text-[12.5px] flex items-center gap-2"
        >
          <AppIcon name="info" :size="15" class="shrink-0" />
          <span>{{ authError }}</span>
        </div>

        <!-- Login Form -->
        <form v-if="authMode === 'login'" @submit.prevent="handleLogin" class="space-y-4">
          <div>
            <label class="block text-[12.5px] font-medium text-charcoal-soft mb-1.5">用户名</label>
            <div class="relative">
              <AppIcon name="user" :size="16" class="absolute left-3.5 top-1/2 -translate-y-1/2 text-warmgray" />
              <input
                v-model.trim="loginForm.username"
                type="text"
                placeholder="请输入用户名（如 demo_user）"
                class="w-full pl-10 pr-4 py-2.5 rounded-2xl bg-oat-50 border border-oat-300 focus:border-charcoal focus:bg-white outline-none text-[13.5px] transition"
              />
            </div>
          </div>

          <div>
            <label class="block text-[12.5px] font-medium text-charcoal-soft mb-1.5">密码</label>
            <div class="relative">
              <AppIcon name="lock" :size="16" class="absolute left-3.5 top-1/2 -translate-y-1/2 text-warmgray" />
              <input
                v-model="loginForm.password"
                :type="showPassword ? 'text' : 'password'"
                placeholder="请输入登录密码"
                class="w-full pl-10 pr-10 py-2.5 rounded-2xl bg-oat-50 border border-oat-300 focus:border-charcoal focus:bg-white outline-none text-[13.5px] transition"
              />
              <button
                type="button"
                @click="showPassword = !showPassword"
                class="absolute right-3.5 top-1/2 -translate-y-1/2 text-warmgray hover:text-charcoal"
              >
                <AppIcon :name="showPassword ? 'eye-off' : 'eye'" :size="16" />
              </button>
            </div>
          </div>

          <div class="flex items-center justify-between text-[12px]">
            <label class="flex items-center gap-2 text-warmgray cursor-pointer select-none">
              <input v-model="loginForm.remember" type="checkbox" class="rounded accent-[#1E1E1E]" />
              保持 7 天 JWT 登录状态
            </label>
            <button type="button" @click="fillDemoCredentials" class="text-terracotta hover:underline font-medium">
              填入演示账号密码
            </button>
          </div>

          <button
            type="submit"
            :disabled="authStore.loading"
            class="w-full py-3 rounded-2xl bg-charcoal hover:bg-charcoal-hover text-white text-[14px] font-medium shadow-sm transition active:scale-[0.99] disabled:opacity-60"
          >
            {{ authStore.loading ? '正在验证登录...' : '登录进入衣橱' }}
          </button>

          <div class="relative py-1">
            <div class="absolute inset-0 flex items-center">
              <div class="w-full border-t border-oat-200" />
            </div>
            <div class="relative flex justify-center text-[11.5px]">
              <span class="bg-white px-3 text-warmgray">答辩 / 快速预览快捷入口</span>
            </div>
          </div>

          <button
            type="button"
            @click="quickDemoLogin"
            :disabled="authStore.loading"
            class="w-full p-3.5 rounded-2xl bg-terracotta-light/65 hover:bg-terracotta-light border border-terracotta/35 text-left transition flex items-center justify-between group"
          >
            <div class="flex items-center gap-3">
              <div
                class="w-10 h-10 rounded-xl bg-terracotta text-white font-serif font-semibold flex items-center justify-center shrink-0"
              >
                林
              </div>
              <div>
                <div class="text-[13.5px] font-semibold text-charcoal">演示账号一键登录（林小满）</div>
                <div class="text-[11.5px] text-terracotta-dark mt-0.5 font-mono">
                  demo_user · 预置 11 件衣物 & 4 条日历
                </div>
              </div>
            </div>
            <AppIcon
              name="arrow-right"
              :size="16"
              class="text-terracotta-dark group-hover:translate-x-1 transition"
            />
          </button>
        </form>

        <!-- Register Form -->
        <form v-else @submit.prevent="handleRegister" class="space-y-3.5">
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <label class="block text-[12px] font-medium text-charcoal-soft mb-1">用户名 *</label>
              <input
                v-model.trim="registerForm.username"
                type="text"
                placeholder="如 yanghuan"
                class="w-full px-3.5 py-2.5 rounded-2xl bg-oat-50 border border-oat-300 focus:border-charcoal focus:bg-white outline-none text-[13px]"
              />
            </div>
            <div>
              <label class="block text-[12px] font-medium text-charcoal-soft mb-1">用户昵称 *</label>
              <input
                v-model.trim="registerForm.nickname"
                type="text"
                placeholder="如 杨欢"
                class="w-full px-3.5 py-2.5 rounded-2xl bg-oat-50 border border-oat-300 focus:border-charcoal focus:bg-white outline-none text-[13px]"
              />
            </div>
          </div>

          <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <label class="block text-[12px] font-medium text-charcoal-soft mb-1">登录密码 *</label>
              <input
                v-model="registerForm.password"
                type="password"
                placeholder="至少 6 位字符"
                class="w-full px-3.5 py-2.5 rounded-2xl bg-oat-50 border border-oat-300 focus:border-charcoal focus:bg-white outline-none text-[13px]"
              />
            </div>
            <div>
              <label class="block text-[12px] font-medium text-charcoal-soft mb-1">确认密码 *</label>
              <input
                v-model="registerForm.confirmPassword"
                type="password"
                placeholder="再次输入密码"
                class="w-full px-3.5 py-2.5 rounded-2xl bg-oat-50 border border-oat-300 focus:border-charcoal focus:bg-white outline-none text-[13px]"
              />
            </div>
          </div>

          <div>
            <label class="block text-[12px] font-medium text-charcoal-soft mb-1">常驻城市（初始化天气偏好）</label>
            <select
              v-model="registerForm.defaultCity"
              class="w-full px-3.5 py-2.5 rounded-2xl bg-oat-50 border border-oat-300 text-[13px] text-charcoal outline-none"
            >
              <option v-for="c in CITY_OPTIONS" :key="c" :value="c">{{ c }}</option>
            </select>
          </div>

          <label
            class="flex items-start gap-2.5 p-3 rounded-2xl bg-oat-50 border border-oat-200 cursor-pointer text-[12px] text-charcoal-soft"
          >
            <input v-model="registerForm.loadStarterPack" type="checkbox" class="mt-0.5 accent-black" />
            <span>
              初始化时导入 <strong>3 件基础款新手衣物</strong>（取消勾选则以纯空白衣橱开始，直观演示多用户数据隔离）
            </span>
          </label>

          <button
            type="submit"
            :disabled="authStore.loading"
            class="w-full py-3 rounded-2xl bg-terracotta hover:bg-terracotta-dark text-white text-[14px] font-medium shadow-sm transition disabled:opacity-60"
          >
            {{ authStore.loading ? '正在创建账号...' : '注册并自动登录' }}
          </button>
        </form>

        <!-- Recent Accounts Switcher -->
        <div v-if="authStore.recentAccounts.length > 1" class="pt-3 border-t border-oat-200">
          <div class="text-[11.5px] text-warmgray mb-2">最近登录过的账号（点击快速填入用户名）：</div>
          <div class="flex flex-wrap gap-1.5">
            <button
              v-for="acc in authStore.recentAccounts"
              :key="acc.username"
              type="button"
              @click="
                authMode = 'login';
                loginForm.username = acc.username;
                loginForm.password = acc.username === 'demo_user' ? 'Wardrobe2026!' : '';
              "
              class="px-2.5 py-1 rounded-full bg-oat-100 hover:bg-oat-200 text-[11.5px] text-charcoal font-mono"
            >
              @{{ acc.username }} ({{ acc.nickname }})
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
