<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useLookbookStore } from '@/stores/lookbook';
import { useToastStore } from '@/stores/toast';
import AppIcon from '@/components/AppIcon.vue';
import ItemDetailDrawer from '@/components/ItemDetailDrawer.vue';
import ProductModal from '@/components/ProductModal.vue';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const wardrobeStore = useWardrobeStore();
const lookbookStore = useLookbookStore();
const toast = useToastStore();

const ready = ref(false);
const createSheetOpen = ref(false);

onMounted(async () => {
  try {
    if (!authStore.user) {
      await authStore.fetchMe();
    }
    if (!authStore.isAuthenticated) {
      router.replace('/login');
      return;
    }
    await wardrobeStore.bootstrap();
    await lookbookStore.bootstrap();
  } finally {
    ready.value = true;
  }
});

const dockItems = [
  { name: 'dashboard', path: '/', label: '首页', icon: 'home' },
  { name: 'wardrobe', path: '/wardrobe', label: '衣橱', icon: 'shirt' },
  { name: 'inspo', path: '/inspo', label: '灵感发现', icon: 'search' },
  { name: 'profile', path: '/profile', label: '我的', icon: 'user' },
];

const quickHeaderLinks = [
  { name: 'lookbook', path: '/lookbook', label: '搭配画板' },
  { name: 'scan', path: '/scan', label: 'AI 识图' },
  { name: 'calendar', path: '/calendar', label: '穿搭日历' },
];

const activeDockIndex = computed(() => {
  const idx = route.meta.dockIndex;
  return typeof idx === 'number' ? idx : 0;
});

const isInspoRoute = computed(() => route.name === 'inspo');

const currentNavTitle = computed(() => String(route.meta.title || '智搭衣橱'));

async function handleCycleWeather() {
  wardrobeStore.cycleWeatherPreset();
  const w = wardrobeStore.activeWeather;
  if (wardrobeStore.usingLiveWeather) {
    toast.show(`已恢复「${w.city}」实时天气：${w.condition} ${w.temp}°C`);
  } else {
    toast.show(`已切换模拟温差：${w.condition} ${w.temp}°C，再次点击可恢复实时天气`);
  }
  if (lookbookStore.triggerMode === 'weather') {
    await lookbookStore.generateRecommendation({ triggerType: 'daily_weather' });
  }
}

function goQuickAction(path: string, query?: Record<string, string>) {
  createSheetOpen.value = false;
  router.push(query ? { path, query } : { path });
}

function handleLogout() {
  createSheetOpen.value = false;
  authStore.logout();
  wardrobeStore.reset();
  lookbookStore.reset();
  toast.show('已安全退出登录，可切换其他账号体验多用户隔离');
  router.replace('/login');
}
</script>

<template>
  <div class="min-h-screen flex flex-col bg-white text-black">
    <!-- Minimal Alta Sticky Top Header -->
    <header class="sticky top-0 z-30 bg-white/90 backdrop-blur-md border-b border-black/[0.06]">
      <div
        class="mx-auto w-full px-4 sm:px-6 h-14 flex items-center justify-between gap-3"
        :class="isInspoRoute ? 'max-w-[920px]' : 'max-w-[1080px]'"
      >
        <!-- Left: Minimal Wordmark + Route Title -->
        <div class="flex items-center gap-3 min-w-0">
          <button
            @click="router.push('/')"
            class="flex items-center gap-2 group focus:outline-none shrink-0"
          >
            <span
              class="w-7 h-7 rounded-full bg-black text-white flex items-center justify-center text-[11px] font-semibold tracking-tight group-hover:scale-105 transition"
            >
              A
            </span>
            <span class="text-[15px] font-semibold tracking-[-0.02em] text-black">ALTA · 智搭</span>
          </button>
          <span class="hidden sm:inline text-black/20">/</span>
          <span class="hidden sm:inline text-[13px] font-normal text-[#666666] truncate">
            {{ currentNavTitle }}
          </span>
        </div>

        <!-- Center: Lightweight Secondary Module Links (Desktop) -->
        <div class="hidden md:flex items-center gap-1 bg-[#F5F5F5] p-1 rounded-full">
          <button
            v-for="link in quickHeaderLinks"
            :key="link.name"
            @click="router.push(link.path)"
            class="px-3.5 py-1 rounded-full text-[12.5px] font-medium transition"
            :class="
              route.name === link.name
                ? 'bg-black text-white shadow-sm'
                : 'text-[#666666] hover:text-black'
            "
          >
            {{ link.label }}
          </button>
        </div>

        <!-- Right: Active Try-On Pill + Weather Capsule + User Trigger -->
        <div class="flex items-center gap-2 shrink-0">
          <button
            v-if="lookbookStore.activeTryOnJobs.length > 0 && route.name !== 'lookbook'"
            type="button"
            @click="router.push({ path: '/lookbook', query: { mode: 'tryon', planId: String(lookbookStore.activeTryOnJobs[0].planId) } })"
            class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-black text-white text-[11.5px] font-medium hover:bg-[#262626] transition"
          >
            <span class="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse" />
            <span>AI 试穿生成中</span>
          </button>

          <button
            @click="handleCycleWeather"
            title="点击切换实时天气 / 模拟温差"
            class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] text-[12px] text-black transition"
          >
            <span
              class="w-1.5 h-1.5 rounded-full"
              :class="wardrobeStore.usingLiveWeather ? 'bg-emerald-500' : 'bg-black'"
            />
            <span class="hidden sm:inline text-[#666666]">{{ wardrobeStore.activeWeather.city }}</span>
            <span class="font-mono font-medium">{{ wardrobeStore.activeWeather.temp }}°C</span>
          </button>

          <button
            @click="router.push('/calendar')"
            title="穿搭日历与收藏"
            class="md:hidden w-8 h-8 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] flex items-center justify-center text-black transition"
          >
            <AppIcon name="calendar" :size="15" />
          </button>

          <button
            @click="handleLogout"
            title="退出登录 / 切换账号"
            class="w-8 h-8 rounded-full bg-[#F5F5F5] hover:bg-black hover:text-white flex items-center justify-center text-[#666666] transition"
          >
            <AppIcon name="log-out" :size="14" />
          </button>
        </div>
      </div>
    </header>

    <!-- Main Centered Gallery Container (generous bottom padding for floating Liquid Glass Dock) -->
    <main
      class="flex-1 w-full mx-auto px-4 sm:px-6 pt-5 pb-28"
      :class="isInspoRoute ? 'max-w-[920px]' : 'max-w-[1080px]'"
    >
      <div v-if="!ready" class="py-24 flex flex-col items-center justify-center gap-3 text-[#666666]">
        <div class="w-8 h-8 rounded-full border-2 border-black border-t-transparent animate-spin" />
        <p class="text-[13px] tracking-wide">正在加载私人数字衣橱...</p>
      </div>
      <router-view v-else />
    </main>

    <!-- Alta Liquid Glass Floating Bottom Dock (Unified Desktop & Mobile) -->
    <nav
      id="alta-floating-dock"
      aria-label="主导航"
      class="fixed inset-x-0 bottom-0 z-40 flex flex-col items-center justify-end pb-4 pt-6 pointer-events-none select-none"
    >
      <!-- Soft bottom fade scrim like Alta -->
      <div
        class="pointer-events-none absolute inset-0 bg-gradient-to-t from-white/90 via-white/45 to-transparent"
      />

      <div class="relative flex h-[56px] w-full max-w-[540px] items-center gap-x-3 px-4 pointer-events-auto">
        <!-- Liquid Glass Pill with Sliding Active Capsule -->
        <div class="alta-glass-dock flex h-[56px] flex-1 items-center rounded-full px-1.5">
          <div class="relative flex h-full w-full items-center">
            <!-- Sliding Active Highlight Pill -->
            <div
              aria-hidden="true"
              class="pointer-events-none absolute inset-y-1 left-0 z-0 rounded-full bg-black/[0.09] transition-transform duration-300 ease-out"
              :style="{
                width: '25%',
                transform: `translateX(${activeDockIndex * 100}%)`,
              }"
            />

            <button
              v-for="(item, idx) in dockItems"
              :key="item.name"
              @click="router.push(item.path)"
              :title="item.label"
              :aria-label="item.label"
              class="relative z-10 flex h-full flex-1 items-center justify-center rounded-full focus:outline-none transition-transform active:scale-95"
            >
              <!-- Profile tab displays user initial avatar circle like Alta -->
              <template v-if="item.name === 'profile'">
                <span
                  class="w-6 h-6 rounded-full flex items-center justify-center text-[11px] font-semibold border transition"
                  :class="
                    activeDockIndex === idx
                      ? 'bg-black text-white border-black'
                      : 'bg-black/80 text-white border-black/15'
                  "
                >
                  {{ (authStore.user?.nickname || '我').slice(0, 1) }}
                </span>
              </template>
              <template v-else>
                <AppIcon
                  :name="item.icon"
                  :size="20"
                  class="transition-opacity"
                  :class="activeDockIndex === idx ? 'text-black opacity-100' : 'text-black opacity-75 hover:opacity-100'"
                />
              </template>
            </button>
          </div>
        </div>

        <!-- Independent 56x56 Black Circular "+" Quick Action Button -->
        <button
          @click="createSheetOpen = !createSheetOpen"
          aria-label="快捷创建与功能菜单"
          title="录入衣物 / 街拍拆解 / 智能搭配"
          class="relative flex h-[56px] w-[56px] shrink-0 items-center justify-center rounded-full bg-black hover:bg-[#222222] text-white shadow-float transition-all duration-200 active:scale-95 focus:outline-none"
        >
          <AppIcon
            name="plus"
            :size="22"
            class="transition-transform duration-200"
            :class="createSheetOpen ? 'rotate-45' : ''"
          />
        </button>
      </div>
    </nav>

    <!-- Alta "+" Floating Action Sheet Overlay -->
    <transition name="modal-fade">
      <div
        v-if="createSheetOpen"
        class="fixed inset-0 z-50 flex items-end justify-center bg-black/25 backdrop-blur-[2px] pb-24 px-4"
        @click.self="createSheetOpen = false"
      >
        <div
          class="modal-sheet w-full max-w-[420px] bg-white rounded-[28px] border border-black/[0.08] shadow-float p-5 space-y-2.5"
        >
          <div class="flex items-center justify-between px-1 pb-1">
            <span class="text-[13px] font-medium text-[#666666]">快捷创作与搭配入口</span>
            <button
              @click="createSheetOpen = false"
              class="w-7 h-7 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] flex items-center justify-center text-black"
            >
              <AppIcon name="x" :size="14" />
            </button>
          </div>

          <button
            @click="goQuickAction('/scan', { mode: 'item' })"
            class="w-full p-3.5 rounded-2xl bg-[#F5F5F5] hover:bg-black hover:text-white transition flex items-center justify-between group text-left"
          >
            <div class="flex items-center gap-3">
              <div class="w-10 h-10 rounded-full bg-white text-black flex items-center justify-center shadow-sm">
                <AppIcon name="camera" :size="18" />
              </div>
              <div>
                <div class="text-[14px] font-semibold">AI 拍照 / 截图录入衣物</div>
                <div class="text-[11.5px] opacity-65 mt-0.5">自动抠图打标，支持填写购入价追踪 CPW</div>
              </div>
            </div>
            <AppIcon name="arrow-right" :size="16" class="opacity-60 group-hover:translate-x-0.5 transition" />
          </button>

          <button
            @click="goQuickAction('/scan', { mode: 'inspiration' })"
            class="w-full p-3.5 rounded-2xl bg-[#F5F5F5] hover:bg-black hover:text-white transition flex items-center justify-between group text-left"
          >
            <div class="flex items-center gap-3">
              <div class="w-10 h-10 rounded-full bg-white text-black flex items-center justify-center shadow-sm">
                <AppIcon name="scan" :size="18" />
              </div>
              <div>
                <div class="text-[14px] font-semibold">AI 街拍拆解找同款</div>
                <div class="text-[11.5px] opacity-65 mt-0.5">上传全身街拍，先查衣橱已有款再推平替</div>
              </div>
            </div>
            <AppIcon name="arrow-right" :size="16" class="opacity-60 group-hover:translate-x-0.5 transition" />
          </button>

          <button
            @click="goQuickAction('/lookbook')"
            class="w-full p-3.5 rounded-2xl bg-[#F5F5F5] hover:bg-black hover:text-white transition flex items-center justify-between group text-left"
          >
            <div class="flex items-center gap-3">
              <div class="w-10 h-10 rounded-full bg-white text-black flex items-center justify-center shadow-sm">
                <AppIcon name="layers" :size="18" />
              </div>
              <div>
                <div class="text-[14px] font-semibold">智能平铺搭配画板</div>
                <div class="text-[11.5px] opacity-65 mt-0.5">天气驱动 / 场景定制 / 单品锚点一衣多搭</div>
              </div>
            </div>
            <AppIcon name="arrow-right" :size="16" class="opacity-60 group-hover:translate-x-0.5 transition" />
          </button>

          <button
            @click="goQuickAction('/calendar')"
            class="w-full p-3.5 rounded-2xl bg-[#F5F5F5] hover:bg-black hover:text-white transition flex items-center justify-between group text-left"
          >
            <div class="flex items-center gap-3">
              <div class="w-10 h-10 rounded-full bg-white text-black flex items-center justify-center shadow-sm">
                <AppIcon name="calendar" :size="18" />
              </div>
              <div>
                <div class="text-[14px] font-semibold">穿搭日历与收藏方案</div>
                <div class="text-[11.5px] opacity-65 mt-0.5">
                  已记录 {{ lookbookStore.history.length }} 天档案 · {{ lookbookStore.favorites.length }} 套收藏
                </div>
              </div>
            </div>
            <AppIcon name="arrow-right" :size="16" class="opacity-60 group-hover:translate-x-0.5 transition" />
          </button>
        </div>
      </div>
    </transition>

    <!-- Global Slide-Over Drawer & Product Modal -->
    <ItemDetailDrawer />
    <ProductModal />
  </div>
</template>
