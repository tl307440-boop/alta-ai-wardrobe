import { defineConfig, type Plugin } from 'vite';
import vue from '@vitejs/plugin-vue';
import fs from 'node:fs';
import path from 'node:path';

/**
 * Serves ONNX Runtime Web's self-hosted /ort/*.mjs and /ort/*.wasm files directly during `vite dev`,
 * preventing Vite's transform middleware from rejecting dynamic ES imports from `/public/ort/`.
 */
function serveOrtWasmPlugin(): Plugin {
  const ortDir = path.resolve(__dirname, 'public', 'ort');
  return {
    name: 'serve-ort-wasm-raw',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const cleanUrl = (req.url || '').split('?')[0];
        if (!cleanUrl.startsWith('/ort/')) return next();
        const fileName = path.basename(cleanUrl);
        const fullPath = path.join(ortDir, fileName);
        if (!fs.existsSync(fullPath) || !fs.statSync(fullPath).isFile()) return next();
        if (fileName.endsWith('.mjs') || fileName.endsWith('.js')) {
          res.setHeader('Content-Type', 'application/javascript; charset=utf-8');
        } else if (fileName.endsWith('.wasm')) {
          res.setHeader('Content-Type', 'application/wasm');
        }
        res.setHeader('Cache-Control', 'public, max-age=3600');
        fs.createReadStream(fullPath).pipe(res);
      });
    },
  };
}

export default defineConfig({
  plugins: [serveOrtWasmPlugin(), vue()],
  optimizeDeps: {
    exclude: ['onnxruntime-web'],
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        timeout: 120000,
        proxyTimeout: 120000,
      },
      '/uploads': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
