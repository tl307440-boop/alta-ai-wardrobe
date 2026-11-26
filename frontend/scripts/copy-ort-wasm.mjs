import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, '..');
const srcDir = path.join(root, 'node_modules', 'onnxruntime-web', 'dist');
const dstDir = path.join(root, 'public', 'ort');

if (!fs.existsSync(srcDir)) {
  process.exit(0);
}

fs.mkdirSync(dstDir, { recursive: true });
const wanted = [
  'ort-wasm-simd-threaded.mjs',
  'ort-wasm-simd-threaded.wasm',
  'ort-wasm-simd-threaded.jsep.mjs',
  'ort-wasm-simd-threaded.jsep.wasm',
];
for (const name of wanted) {
  const src = path.join(srcDir, name);
  const dst = path.join(dstDir, name);
  if (fs.existsSync(src)) {
    const needCopy = !fs.existsSync(dst) || fs.statSync(src).size !== fs.statSync(dst).size;
    if (needCopy) {
      fs.copyFileSync(src, dst);
    }
  }
}
