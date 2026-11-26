<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import AppIcon from './AppIcon.vue';

const props = defineProps<{
  open: boolean;
  title?: string;
  originalUrl: string;
  cutoutUrl: string;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'apply', payload: { file: File; dataUrl: string }): void;
}>();

type ToolMode = 'erase' | 'restore';
type BackdropMode = 'checker' | 'white' | 'black';

const toolMode = ref<ToolMode>('erase');
const backdrop = ref<BackdropMode>('checker');
const brushSize = ref(28);
const softEdge = ref(true);
const zoom = ref(1);
const panX = ref(0);
const panY = ref(0);
const spacePressed = ref(false);
const loading = ref(false);
const applying = ref(false);
const loadError = ref('');

const viewportRef = ref<HTMLElement | null>(null);
const displayCanvasRef = ref<HTMLCanvasElement | null>(null);

// Offscreen buffers: origCanvas holds untouched RGB source for Restore; workCanvas holds live RGBA cutout.
let origCanvas: HTMLCanvasElement | null = null;
let workCanvas: HTMLCanvasElement | null = null;
let strokeCanvas: HTMLCanvasElement | null = null;
let imgW = 512;
let imgH = 512;

// 20-step stroke undo/redo stack (pushed only on pointerup)
const historyStack = ref<ImageData[]>([]);
const historyIdx = ref(-1);
let initialAiSnapshot: ImageData | null = null;

const canUndo = computed(() => historyIdx.value > 0);
const canRedo = computed(() => historyIdx.value < historyStack.value.length - 1);

// Custom circular brush cursor tracking
const cursorPos = ref<{ x: number; y: number; visible: boolean }>({ x: 0, y: 0, visible: false });
const cursorDiameterPx = computed(() => {
  const c = displayCanvasRef.value;
  if (!c || imgW <= 0) return brushSize.value;
  const rect = c.getBoundingClientRect();
  return Math.max(8, (brushSize.value / imgW) * rect.width);
});

let isPainting = false;
let isPanning = false;
let panStart = { x: 0, y: 0, ox: 0, oy: 0 };
let lastPt: { x: number; y: number } | null = null;

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('图片加载失败'));
    img.src = src;
  });
}

async function initEditor() {
  if (!props.open) return;
  loading.value = true;
  loadError.value = '';
  zoom.value = 1;
  panX.value = 0;
  panY.value = 0;
  toolMode.value = 'erase';
  historyStack.value = [];
  historyIdx.value = -1;
  initialAiSnapshot = null;

  try {
    const primarySrc = props.cutoutUrl || props.originalUrl;
    const origSrc = props.originalUrl || props.cutoutUrl;
    const [cutImg, origImg] = await Promise.all([
      loadImage(primarySrc),
      origSrc === primarySrc ? Promise.resolve(null) : loadImage(origSrc).catch(() => null),
    ]);

    const maxEdge = 960;
    const baseW = cutImg.naturalWidth || 512;
    const baseH = cutImg.naturalHeight || 512;
    const scale = Math.min(1, maxEdge / Math.max(baseW, baseH));
    imgW = Math.max(64, Math.round(baseW * scale));
    imgH = Math.max(64, Math.round(baseH * scale));

    origCanvas = document.createElement('canvas');
    origCanvas.width = imgW;
    origCanvas.height = imgH;
    const octx = origCanvas.getContext('2d', { willReadFrequently: true });
    if (!octx) throw new Error('Canvas 不可用');
    if (origImg) {
      octx.drawImage(origImg, 0, 0, imgW, imgH);
    } else {
      octx.drawImage(cutImg, 0, 0, imgW, imgH);
    }

    workCanvas = document.createElement('canvas');
    workCanvas.width = imgW;
    workCanvas.height = imgH;
    const wctx = workCanvas.getContext('2d', { willReadFrequently: true });
    if (!wctx) throw new Error('Canvas 不可用');
    wctx.clearRect(0, 0, imgW, imgH);
    wctx.drawImage(cutImg, 0, 0, imgW, imgH);

    strokeCanvas = document.createElement('canvas');
    strokeCanvas.width = imgW;
    strokeCanvas.height = imgH;

    initialAiSnapshot = wctx.getImageData(0, 0, imgW, imgH);
    pushHistorySnapshot(initialAiSnapshot);

    loading.value = false;
    await nextTick();
    renderDisplay();
  } catch (e: any) {
    loadError.value = e?.message || '无法加载图片进入精修编辑器';
    loading.value = false;
  }
}

watch(displayCanvasRef, (el) => {
  if (el && workCanvas) {
    renderDisplay();
  }
});

function renderDisplay() {
  const display = displayCanvasRef.value;
  if (!display || !workCanvas) return;
  if (display.width !== imgW || display.height !== imgH) {
    display.width = imgW;
    display.height = imgH;
  }
  const dctx = display.getContext('2d');
  if (!dctx) return;
  dctx.clearRect(0, 0, imgW, imgH);
  dctx.drawImage(workCanvas, 0, 0);
}

function pushHistorySnapshot(snapshot: ImageData) {
  const next = historyStack.value.slice(0, historyIdx.value + 1);
  next.push(snapshot);
  if (next.length > 20) next.shift();
  historyStack.value = next;
  historyIdx.value = next.length - 1;
}

function undo() {
  if (!canUndo.value || !workCanvas) return;
  historyIdx.value -= 1;
  const wctx = workCanvas.getContext('2d', { willReadFrequently: true });
  if (wctx) {
    wctx.putImageData(historyStack.value[historyIdx.value], 0, 0);
    renderDisplay();
  }
}

function redo() {
  if (!canRedo.value || !workCanvas) return;
  historyIdx.value += 1;
  const wctx = workCanvas.getContext('2d', { willReadFrequently: true });
  if (wctx) {
    wctx.putImageData(historyStack.value[historyIdx.value], 0, 0);
    renderDisplay();
  }
}

function resetToAiCutout() {
  if (!initialAiSnapshot || !workCanvas) return;
  const wctx = workCanvas.getContext('2d', { willReadFrequently: true });
  if (!wctx) return;
  wctx.putImageData(initialAiSnapshot, 0, 0);
  pushHistorySnapshot(wctx.getImageData(0, 0, imgW, imgH));
  renderDisplay();
}

function restoreFullOriginal() {
  if (!origCanvas || !workCanvas) return;
  const wctx = workCanvas.getContext('2d', { willReadFrequently: true });
  if (!wctx) return;
  wctx.clearRect(0, 0, imgW, imgH);
  wctx.drawImage(origCanvas, 0, 0);
  pushHistorySnapshot(wctx.getImageData(0, 0, imgW, imgH));
  renderDisplay();
}

function clientToImagePoint(clientX: number, clientY: number): { x: number; y: number } | null {
  const display = displayCanvasRef.value;
  if (!display) return null;
  const rect = display.getBoundingClientRect();
  if (rect.width <= 0 || rect.height <= 0) return null;
  return {
    x: ((clientX - rect.left) / rect.width) * imgW,
    y: ((clientY - rect.top) / rect.height) * imgH,
  };
}

function paintSegment(from: { x: number; y: number }, to: { x: number; y: number }) {
  if (!workCanvas || !origCanvas || !strokeCanvas) return;
  const wctx = workCanvas.getContext('2d', { willReadFrequently: true });
  const sctx = strokeCanvas.getContext('2d');
  if (!wctx || !sctx) return;

  const blurPx = softEdge.value ? Math.max(1.5, brushSize.value * 0.22) : 0;

  if (toolMode.value === 'erase') {
    wctx.save();
    wctx.globalCompositeOperation = 'destination-out';
    wctx.lineCap = 'round';
    wctx.lineJoin = 'round';
    wctx.lineWidth = brushSize.value;
    wctx.strokeStyle = 'rgba(0,0,0,1)';
    if (blurPx > 0) {
      wctx.shadowColor = 'rgba(0,0,0,1)';
      wctx.shadowBlur = blurPx;
    }
    wctx.beginPath();
    wctx.moveTo(from.x, from.y);
    wctx.quadraticCurveTo(from.x, from.y, to.x, to.y);
    wctx.stroke();
    wctx.restore();
  } else {
    // Restore: build soft stroke mask in strokeCanvas, mask origCanvas into it, composite onto workCanvas
    sctx.save();
    sctx.clearRect(0, 0, imgW, imgH);
    sctx.globalCompositeOperation = 'source-over';
    sctx.lineCap = 'round';
    sctx.lineJoin = 'round';
    sctx.lineWidth = brushSize.value;
    sctx.strokeStyle = 'rgba(255,255,255,1)';
    if (blurPx > 0) {
      sctx.shadowColor = 'rgba(255,255,255,1)';
      sctx.shadowBlur = blurPx;
    }
    sctx.beginPath();
    sctx.moveTo(from.x, from.y);
    sctx.quadraticCurveTo(from.x, from.y, to.x, to.y);
    sctx.stroke();

    sctx.shadowBlur = 0;
    sctx.globalCompositeOperation = 'source-in';
    sctx.drawImage(origCanvas, 0, 0);
    sctx.restore();

    wctx.save();
    wctx.globalCompositeOperation = 'source-over';
    wctx.drawImage(strokeCanvas, 0, 0);
    wctx.restore();
  }

  renderDisplay();
}

function onViewportPointerDown(e: PointerEvent) {
  if (spacePressed.value || e.button === 1) {
    isPanning = true;
    panStart = { x: e.clientX, y: e.clientY, ox: panX.value, oy: panY.value };
    (e.currentTarget as HTMLElement)?.setPointerCapture?.(e.pointerId);
    return;
  }
  if (e.button !== 0) return;
  const pt = clientToImagePoint(e.clientX, e.clientY);
  if (!pt) return;
  isPainting = true;
  lastPt = pt;
  (e.currentTarget as HTMLElement)?.setPointerCapture?.(e.pointerId);
  paintSegment(pt, { x: pt.x + 0.01, y: pt.y + 0.01 });
}

function onViewportPointerMove(e: PointerEvent) {
  const vpRect = viewportRef.value?.getBoundingClientRect();
  if (vpRect) {
    cursorPos.value = {
      x: e.clientX - vpRect.left,
      y: e.clientY - vpRect.top,
      visible: !spacePressed.value && !isPanning,
    };
  }
  if (isPanning) {
    panX.value = panStart.ox + (e.clientX - panStart.x);
    panY.value = panStart.oy + (e.clientY - panStart.y);
    return;
  }
  if (!isPainting || !lastPt) return;
  const pt = clientToImagePoint(e.clientX, e.clientY);
  if (!pt) return;
  paintSegment(lastPt, pt);
  lastPt = pt;
}

function onViewportPointerUp() {
  if (isPanning) {
    isPanning = false;
    return;
  }
  if (isPainting && workCanvas) {
    isPainting = false;
    lastPt = null;
    const wctx = workCanvas.getContext('2d', { willReadFrequently: true });
    if (wctx) {
      pushHistorySnapshot(wctx.getImageData(0, 0, imgW, imgH));
    }
  }
}

function onViewportWheel(e: WheelEvent) {
  e.preventDefault();
  const factor = e.deltaY < 0 ? 1.12 : 1 / 1.12;
  zoom.value = Number(Math.max(0.5, Math.min(4.0, zoom.value * factor)).toFixed(2));
}

function onKeyDown(e: KeyboardEvent) {
  if (!props.open) return;
  if (e.code === 'Space') {
    spacePressed.value = true;
    cursorPos.value.visible = false;
    return;
  }
  if (e.key === 'Escape') {
    emit('close');
    return;
  }
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') {
    e.preventDefault();
    if (e.shiftKey) redo();
    else undo();
    return;
  }
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'y') {
    e.preventDefault();
    redo();
    return;
  }
  if (e.key === '[') {
    brushSize.value = Math.max(6, brushSize.value - 6);
  } else if (e.key === ']') {
    brushSize.value = Math.min(120, brushSize.value + 6);
  } else if (e.key.toLowerCase() === 'e') {
    toolMode.value = 'erase';
  } else if (e.key.toLowerCase() === 'r') {
    toolMode.value = 'restore';
  }
}

function onKeyUp(e: KeyboardEvent) {
  if (e.code === 'Space') {
    spacePressed.value = false;
  }
}

async function applyRefinement() {
  if (!workCanvas || applying.value) return;
  applying.value = true;
  try {
    const blob = await new Promise<Blob>((resolve, reject) =>
      workCanvas!.toBlob((b) => (b ? resolve(b) : reject(new Error('导出透明图失败'))), 'image/png')
    );
    const file = new File([blob], `refined-${Date.now()}.png`, { type: 'image/png' });
    const dataUrl = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    });
    emit('apply', { file, dataUrl });
  } finally {
    applying.value = false;
  }
}

watch(
  () => [props.open, props.cutoutUrl, props.originalUrl],
  ([isOpen]) => {
    if (isOpen) {
      document.body.style.overflow = 'hidden';
      void initEditor();
    } else {
      document.body.style.overflow = '';
    }
  },
  { immediate: true }
);

onMounted(() => {
  window.addEventListener('keydown', onKeyDown);
  window.addEventListener('keyup', onKeyUp);
});

onBeforeUnmount(() => {
  document.body.style.overflow = '';
  window.removeEventListener('keydown', onKeyDown);
  window.removeEventListener('keyup', onKeyUp);
});

const stageBackdropClass = computed(() => {
  if (backdrop.value === 'white') return 'bg-white';
  if (backdrop.value === 'black') return 'bg-[#121212]';
  return 'cutout-checker';
});
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="fixed inset-0 z-[95] bg-black/55 backdrop-blur-sm flex items-center justify-center p-3 sm:p-6 select-none"
      role="dialog"
      aria-modal="true"
      aria-label="抠图边缘画笔精修"
    >
      <div class="w-full max-w-4xl h-[88vh] bg-white rounded-3xl border border-black/10 shadow-2xl flex flex-col overflow-hidden">
        <!-- Top Bar -->
        <header class="px-5 py-3.5 border-b border-black/[0.08] flex flex-wrap items-center justify-between gap-3">
          <div class="flex items-center gap-2.5 min-w-0">
            <span class="px-2 py-0.5 rounded bg-black text-white font-mono text-[10px] tracking-wider">MASK</span>
            <h3 class="text-[15px] font-semibold text-black truncate">
              抠图边缘精修{{ title ? ` · ${title}` : '' }}
            </h3>
          </div>

          <div class="flex items-center gap-2">
            <div class="inline-flex items-center rounded-full bg-[#F5F5F5] p-0.5">
              <button
                type="button"
                @click="undo"
                :disabled="!canUndo"
                title="撤销 (Ctrl+Z)"
                class="w-7 h-7 rounded-full flex items-center justify-center text-black hover:bg-white disabled:opacity-30 transition"
              >
                <AppIcon name="undo" :size="14" />
              </button>
              <button
                type="button"
                @click="redo"
                :disabled="!canRedo"
                title="重做 (Ctrl+Y)"
                class="w-7 h-7 rounded-full flex items-center justify-center text-black hover:bg-white disabled:opacity-30 transition"
              >
                <AppIcon name="redo" :size="14" />
              </button>
            </div>

            <button
              type="button"
              @click="resetToAiCutout"
              class="px-3 py-1.5 rounded-full bg-[#F5F5F5] hover:bg-[#EAEAEA] text-[12px] text-black font-medium transition"
            >
              重置 AI 抠图
            </button>
            <button
              type="button"
              @click="restoreFullOriginal"
              class="px-3 py-1.5 rounded-full bg-[#F5F5F5] hover:bg-[#EAEAEA] text-[12px] text-[#555555] hover:text-black transition"
            >
              恢复整张原图
            </button>

            <button
              type="button"
              @click="emit('close')"
              class="w-8 h-8 rounded-full bg-[#F5F5F5] hover:bg-[#EBEBEB] flex items-center justify-center text-black transition"
              aria-label="关闭"
            >
              <AppIcon name="x" :size="15" />
            </button>
          </div>
        </header>

        <!-- Interactive Brush Canvas Viewport -->
        <div
          ref="viewportRef"
          @pointerdown="onViewportPointerDown"
          @pointermove="onViewportPointerMove"
          @pointerup="onViewportPointerUp"
          @pointercancel="onViewportPointerUp"
          @pointerleave="cursorPos.visible = false"
          @wheel="onViewportWheel"
          class="flex-1 min-h-0 relative overflow-hidden bg-[#F2F2F2] flex items-center justify-center touch-none"
          :class="spacePressed ? 'cursor-grab' : 'cursor-none'"
        >
          <div v-if="loading" class="text-[13px] text-[#666666] font-mono">正在加载高清画布…</div>
          <div v-else-if="loadError" class="text-[13px] text-red-600">{{ loadError }}</div>

          <div
            v-else
            class="rounded-2xl shadow-lg border border-black/10 overflow-hidden"
            :class="stageBackdropClass"
            :style="{
              transform: `translate(${panX}px, ${panY}px) scale(${zoom})`,
              transformOrigin: 'center center',
            }"
          >
            <canvas
              ref="displayCanvasRef"
              class="block max-w-[74vw] sm:max-w-[540px] max-h-[58vh] w-auto h-auto"
            />
          </div>

          <!-- Real-size Circular Brush Cursor -->
          <div
            v-if="cursorPos.visible && !loading && !loadError"
            class="pointer-events-none absolute rounded-full -translate-x-1/2 -translate-y-1/2 border-2 transition-colors"
            :class="
              toolMode === 'erase'
                ? 'border-black bg-black/10 shadow-[0_0_0_1px_rgba(255,255,255,0.85)]'
                : 'border-emerald-600 bg-emerald-500/15 shadow-[0_0_0_1px_rgba(255,255,255,0.85)]'
            "
            :style="{
              left: `${cursorPos.x}px`,
              top: `${cursorPos.y}px`,
              width: `${cursorDiameterPx}px`,
              height: `${cursorDiameterPx}px`,
            }"
          />

          <!-- Top-Right Backdrop Switcher (Spot white/dark halos immediately) -->
          <div
            @pointerdown.stop
            class="absolute top-3.5 right-3.5 inline-flex items-center gap-1 p-1 rounded-full bg-white/95 border border-black/10 shadow-sm text-[11.5px]"
          >
            <button
              v-for="bg in ([
                { id: 'checker', label: '棋盘格' },
                { id: 'white', label: '纯白底' },
                { id: 'black', label: '纯黑底' },
              ] as const)"
              :key="bg.id"
              type="button"
              @click="backdrop = bg.id"
              class="px-2.5 py-1 rounded-full transition"
              :class="backdrop === bg.id ? 'bg-black text-white font-medium' : 'text-[#666666] hover:text-black'"
            >
              {{ bg.label }}
            </button>
          </div>

          <!-- Bottom-Right Zoom Pill -->
          <div
            @pointerdown.stop
            class="absolute bottom-3.5 right-3.5 inline-flex items-center gap-1 px-2 py-1 rounded-full bg-white/95 border border-black/10 shadow-sm font-mono text-[11px]"
          >
            <button type="button" @click="zoom = Math.max(0.5, Number((zoom - 0.25).toFixed(2)))" class="w-5 h-5 rounded-full hover:bg-[#F2F2F2]">−</button>
            <button type="button" @click="zoom = 1; panX = 0; panY = 0" class="px-1 hover:underline">{{ Math.round(zoom * 100) }}%</button>
            <button type="button" @click="zoom = Math.min(4, Number((zoom + 0.25).toFixed(2)))" class="w-5 h-5 rounded-full hover:bg-[#F2F2F2]">+</button>
          </div>
        </div>

        <!-- Bottom Brush Controls & Confirm -->
        <footer class="px-5 py-3.5 border-t border-black/[0.08] bg-white flex flex-wrap items-center justify-between gap-3">
          <div class="flex flex-wrap items-center gap-3">
            <!-- Erase / Restore Toggle -->
            <div class="inline-flex p-0.5 rounded-full bg-[#F5F5F5] text-[12.5px] font-medium">
              <button
                type="button"
                @click="toolMode = 'erase'"
                class="px-3.5 py-1.5 rounded-full transition"
                :class="toolMode === 'erase' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
              >
                擦除多余 (E)
              </button>
              <button
                type="button"
                @click="toolMode = 'restore'"
                class="px-3.5 py-1.5 rounded-full transition"
                :class="toolMode === 'restore' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
              >
                涂抹恢复 (R)
              </button>
            </div>

            <!-- Brush Size Slider -->
            <div class="flex items-center gap-2 text-[12px] text-[#666666]">
              <span>笔刷</span>
              <input v-model.number="brushSize" type="range" min="6" max="120" step="2" class="w-24 sm:w-32 accent-black" />
              <span class="font-mono text-black w-9">{{ brushSize }}px</span>
            </div>

            <!-- Soft / Hard Edge Toggle -->
            <button
              type="button"
              @click="softEdge = !softEdge"
              class="px-3 py-1 rounded-full border text-[12px] transition"
              :class="softEdge ? 'border-black bg-black/5 text-black font-medium' : 'border-black/15 text-[#666666]'"
            >
              {{ softEdge ? '柔边羽化' : '硬边精确' }}
            </button>

            <span class="hidden lg:inline text-[11px] text-[#888888] font-mono">
              滚轮缩放 · 空格拖拽 · [ / ] 调笔刷
            </span>
          </div>

          <div class="flex items-center gap-2">
            <button
              type="button"
              @click="emit('close')"
              class="px-4 py-2 rounded-full bg-[#F5F5F5] hover:bg-[#EAEAEA] text-[13px] font-medium text-black transition"
            >
              取消
            </button>
            <button
              type="button"
              @click="applyRefinement"
              :disabled="applying || loading || !!loadError"
              class="px-5 py-2 rounded-full bg-black hover:bg-[#262626] text-white text-[13px] font-medium transition disabled:opacity-40 flex items-center gap-1.5"
            >
              <AppIcon name="check" :size="14" />
              {{ applying ? '保存中…' : '应用精修抠图' }}
            </button>
          </div>
        </footer>
      </div>
    </div>
  </Teleport>
</template>
