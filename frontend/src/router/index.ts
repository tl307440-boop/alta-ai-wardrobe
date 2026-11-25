import { createRouter, createWebHistory } from 'vue-router';
import { TOKEN_KEY } from '@/api/http';

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior(to, from) {
    // 同页切换 query（如 /profile?tab=...）不回顶，保持当前滚动位置
    if (to.path === from.path) return false;
    return { top: 0, behavior: 'smooth' };
  },
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/AuthView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: () => import('@/views/AppLayout.vue'),
      children: [
        {
          path: '',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: '今日精选', dockIndex: 0 },
        },
        {
          path: 'wardrobe',
          name: 'wardrobe',
          component: () => import('@/views/WardrobeView.vue'),
          meta: { title: '我的衣橱', dockIndex: 1 },
        },
        {
          path: 'inspo',
          name: 'inspo',
          component: () => import('@/views/InspoView.vue'),
          meta: { title: '灵感发现', dockIndex: 2 },
        },
        {
          path: 'profile',
          name: 'profile',
          component: () => import('@/views/ProfileView.vue'),
          meta: { title: '我的', dockIndex: 3 },
        },
        {
          path: 'lookbook',
          name: 'lookbook',
          component: () => import('@/views/LookbookView.vue'),
          meta: { title: '智能搭配画板', dockIndex: 0 },
        },
        {
          path: 'scan',
          name: 'scan',
          component: () => import('@/views/VisionStudioView.vue'),
          meta: { title: 'AI 识别', dockIndex: 1 },
        },
        {
          path: 'calendar',
          name: 'calendar',
          component: () => import('@/views/CalendarView.vue'),
          meta: { title: '穿搭日历与收藏', dockIndex: 3 },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
});

router.beforeEach((to) => {
  const token = localStorage.getItem(TOKEN_KEY) || sessionStorage.getItem(TOKEN_KEY);
  if (!to.meta.public && !token) {
    return { name: 'login' };
  }
  if (to.name === 'login' && token) {
    return { name: 'dashboard' };
  }
  return true;
});

export default router;
