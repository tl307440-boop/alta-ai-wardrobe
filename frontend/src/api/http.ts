import axios from 'axios';
import type { ApiResult } from '@/types';

export const TOKEN_KEY = 'ai_wardrobe_jwt_token';

const http = axios.create({
  baseURL: '/api',
  timeout: 45000,
});

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY) || sessionStorage.getItem(TOKEN_KEY);
  if (token) {
    config.headers = config.headers || {};
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

http.interceptors.response.use(
  (response) => {
    const payload = response.data as ApiResult<unknown>;
    if (payload && typeof payload.code === 'number' && payload.code !== 200) {
      return Promise.reject(new Error(payload.message || '请求失败'));
    }
    return response;
  },
  (error) => {
    const status = error.response?.status;
    const payload = error.response?.data as ApiResult<unknown> | undefined;
    if (status === 401 || payload?.code === 40101) {
      const isAuthEndpoint = error.config?.url?.includes('/auth/login') || error.config?.url?.includes('/auth/register');
      if (!isAuthEndpoint) {
        localStorage.removeItem(TOKEN_KEY);
        sessionStorage.removeItem(TOKEN_KEY);
        if (window.location.pathname !== '/login') {
          window.location.href = '/login';
        }
      }
    }
    const message = payload?.message || error.message || '网络连接异常，请确认后端服务已启动';
    return Promise.reject(new Error(message));
  }
);

export async function unwrap<T>(promise: Promise<{ data: ApiResult<T> }>): Promise<T> {
  const res = await promise;
  return res.data.data;
}

export default http;
