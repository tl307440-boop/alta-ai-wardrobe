import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { authApi } from '@/api/modules';
import { TOKEN_KEY } from '@/api/http';
import type { UserInfo } from '@/types';

const RECENT_ACCOUNTS_KEY = 'ai_wardrobe_recent_accounts';

export interface RecentAccount {
  username: string;
  nickname: string;
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) || sessionStorage.getItem(TOKEN_KEY) || '');
  const user = ref<UserInfo | null>(null);
  const loading = ref(false);
  const recentAccounts = ref<RecentAccount[]>(loadRecentAccounts());

  const isAuthenticated = computed(() => Boolean(token.value));

  function loadRecentAccounts(): RecentAccount[] {
    try {
      const raw = localStorage.getItem(RECENT_ACCOUNTS_KEY);
      if (raw) return JSON.parse(raw);
    } catch {
      // ignore
    }
    return [{ username: 'demo_user', nickname: '林小满' }];
  }

  function rememberAccount(u: UserInfo) {
    const exists = recentAccounts.value.find((a) => a.username === u.username);
    if (!exists) {
      recentAccounts.value.push({ username: u.username, nickname: u.nickname });
    } else {
      exists.nickname = u.nickname;
    }
    localStorage.setItem(RECENT_ACCOUNTS_KEY, JSON.stringify(recentAccounts.value));
  }

  function saveToken(newToken: string, remember = true) {
    token.value = newToken;
    localStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(TOKEN_KEY);
    if (remember) {
      localStorage.setItem(TOKEN_KEY, newToken);
    } else {
      sessionStorage.setItem(TOKEN_KEY, newToken);
    }
  }

  async function login(username: string, password: string, remember = true) {
    loading.value = true;
    try {
      const res = await authApi.login({ username, password });
      saveToken(res.token, remember);
      user.value = res.userInfo;
      rememberAccount(res.userInfo);
      return res;
    } finally {
      loading.value = false;
    }
  }

  async function register(payload: {
    username: string;
    nickname: string;
    password: string;
    defaultCity: string;
    loadStarterPack: boolean;
  }) {
    loading.value = true;
    try {
      const res = await authApi.register(payload);
      saveToken(res.token, true);
      user.value = res.userInfo;
      rememberAccount(res.userInfo);
      return res;
    } finally {
      loading.value = false;
    }
  }

  async function fetchMe() {
    if (!token.value) return null;
    try {
      const info = await authApi.me();
      user.value = info;
      rememberAccount(info);
      return info;
    } catch {
      logout();
      return null;
    }
  }

  function logout() {
    token.value = '';
    user.value = null;
    localStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(TOKEN_KEY);
  }

  return {
    token,
    user,
    loading,
    recentAccounts,
    isAuthenticated,
    login,
    register,
    fetchMe,
    logout,
  };
});
