<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import type { CollageNode, OutfitPlanVo, PlanSlot, WardrobeItem } from '@/types';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useToastStore } from '@/stores/toast';
import AppIcon from './AppIcon.vue';
import GarmentVisual from './GarmentVisual.vue';
import FlatLayStudioModal from './FlatLayStudioModal.vue';

const props = defineProps<{
  plan: OutfitPlanVo;
  saving?: boolean;
}>();

const emit = defineEmits<{
  (e: 'save-layout', nodes: CollageNode[]): void;
  (e: 'swap-slot', slotKey: string, currentItemId: number): void;
  (e: 'remove-slot', itemId: number): void;
  (e: 'open-add-item'): void;
}>();

const route = useRoute();
const router = useRouter();
const wardrobeStore = useWardrobeStore();
const toast = useToastStore();

const canvasRef = ref<HTMLElement | null>(null);
const nodes = ref<CollageNode[]>([]);
const selectedKey = ref<string | null>(null);
const exporting = ref(false);
const hasUnsavedChanges = ref(false);
const studioOpen = ref(route.query.studio === '1');

watch(
  () => route.query.studio,
  (q) => {
    studioOpen.value = q === '1';
  }
);

function openStudio() {
  studioOpen.value = true;
  if (route.query.studio !== '1') {
    router.replace({
      path: route.path,
      query: { ...route.query, mode: 'collage', studio: '1' },
    });
  }
}

function closeStudio() {
  studioOpen.value = false;
  if (route.query.studio) {
    const nextQuery = { ...route.query };
    delete nextQuery.studio;
    router.replace({ path: route.path, query: nextQuery });
  }
}

interface DragState {
  nodeKey: string;
  startClientX: number;
  startClientY: number;
  origX: number;
  origY: number;
  canvasW: number;
  canvasH: number;
}
const dragState = ref<DragState | null>(null);

function makeNodeKey(slot: PlanSlot, idx: number): string {
  return `${slot.slotKey}-${slot.item.id}-${idx}`;
}

function buildDefaultLayout(slots: PlanSlot[]): CollageNode[] {
  const categoryPresets: Record<string, { x: number; y: number; scale: number; rotation: number; zIndex: number }> = {
    outerwear: { x: 27, y: 36, scale: 1.14, rotation: -4, zIndex: 4 },
    top: { x: 54, y: 27, scale: 0.98, rotation: 2, zIndex: 2 },
    dress: { x: 48, y: 46, scale: 1.18, rotation: -1, zIndex: 3 },
    bottom: { x: 48, y: 65, scale: 1.12, rotation: -1, zIndex: 3 },
    shoes: { x: 75, y: 76, scale: 0.88, rotation: 5, zIndex: 5 },
    bag: { x: 78, y: 36, scale: 0.84, rotation: 5, zIndex: 6 },
    accessory: { x: 22, y: 74, scale: 0.78, rotation: -4, zIndex: 7 },
  };
  const usedCounts: Record<string, number> = {};

  return slots.map((slot, idx) => {
    const preset = categoryPresets[slot.slotKey] || {
      x: 28 + (idx % 3) * 22,
      y: 28 + Math.floor(idx / 3) * 34,
      scale: 0.95,
      rotation: idx % 2 === 0 ? -3 : 3,
      zIndex: idx + 1,
    };
    const count = usedCounts[slot.slotKey] || 0;
    usedCounts[slot.slotKey] = count + 1;
    return {
      nodeKey: makeNodeKey(slot, idx),
      slotKey: slot.slotKey,
      itemId: slot.item.id,
      x: Math.min(86, Math.max(14, preset.x + count * 8)),
      y: Math.min(84, Math.max(16, preset.y + count * 6)),
      scale: preset.scale,
      rotation: preset.rotation,
      zIndex: preset.zIndex + count,
      flipX: false,
    };
  });
}

function hydrateNodes(plan: OutfitPlanVo) {
  const slots = plan.slots || [];
  const defaults = buildDefaultLayout(slots);
  const saved = plan.collageLayout || [];
  if (saved.length === 0) {
    nodes.value = defaults;
    hasUnsavedChanges.value = false;
    return;
  }

  // Reconcile slot items (in case user swapped a slot) while keeping any extra studio pieces/accessories/stamps
  const usedSavedIndices = new Set<number>();
  const mergedSlots: CollageNode[] = defaults.map((def) => {
    let idx = saved.findIndex((s, i) => !usedSavedIndices.has(i) && s.nodeKey === def.nodeKey);
    if (idx < 0 && def.itemId > 0) {
      idx = saved.findIndex(
        (s, i) => !usedSavedIndices.has(i) && !s.labelText && s.itemId === def.itemId
      );
    }
    if (idx < 0) {
      idx = saved.findIndex(
        (s, i) => !usedSavedIndices.has(i) && !s.labelText && s.slotKey === def.slotKey && !s.nodeKey?.includes('-copy-') && !s.nodeKey?.startsWith('wardrobe-') && !s.nodeKey?.startsWith('acc-')
      );
    }
    if (idx < 0) return def;
    usedSavedIndices.add(idx);
    const match = saved[idx];
    return {
      ...def,
      x: typeof match.x === 'number' ? match.x : def.x,
      y: typeof match.y === 'number' ? match.y : def.y,
      scale: typeof match.scale === 'number' ? match.scale : def.scale,
      rotation: typeof match.rotation === 'number' ? match.rotation : def.rotation,
      zIndex: typeof match.zIndex === 'number' ? match.zIndex : def.zIndex,
      flipX: Boolean(match.flipX),
    };
  });

  const extraNodes: CollageNode[] = saved
    .filter((_, i) => !usedSavedIndices.has(i))
    .map((s) => ({ ...s }));

  nodes.value = [...mergedSlots, ...extraNodes];
  hasUnsavedChanges.value = false;
}

watch(
  () => [props.plan.id, props.plan.slots.map((s) => s.item.id).join(',')],
  () => {
    hydrateNodes(props.plan);
    selectedKey.value = null;
  },
  { immediate: true }
);

const slotMap = computed(() => {
  const map: Record<string, PlanSlot> = {};
  (props.plan.slots || []).forEach((s, idx) => {
    map[makeNodeKey(s, idx)] = s;
  });
  return map;
});

const itemById = computed(() => {
  const map: Record<number, WardrobeItem> = {};
  for (const it of wardrobeStore.items) map[it.id] = it;
  for (const s of props.plan.slots || []) map[s.item.id] = s.item;
  return map;
});

function resolveNodeVisual(node: CollageNode) {
  if (node.labelText) {
    return { isText: true, name: node.labelText, imageUrl: '', svgType: undefined, colorHex: '#000000' };
  }
  const slot = slotMap.value[node.nodeKey];
  if (slot) {
    return {
      isText: false,
      name: slot.item.name,
      imageUrl: slot.item.processedImageUrl,
      svgType: slot.item.svgType,
      colorHex: slot.item.colorHex,
    };
  }
  if (node.itemId > 0 && itemById.value[node.itemId]) {
    const it = itemById.value[node.itemId];
    return {
      isText: false,
      name: it.name,
      imageUrl: it.processedImageUrl,
      svgType: it.svgType,
      colorHex: it.colorHex,
    };
  }
  return {
    isText: false,
    name: node.customName || '搭配单品',
    imageUrl: node.customImageUrl,
    svgType: node.customSvgType,
    colorHex: node.customColorHex || '#EFECE6',
  };
}

const selectedNode = computed(() => nodes.value.find((n) => n.nodeKey === selectedKey.value) || null);
const selectedSlot = computed(() => (selectedKey.value ? slotMap.value[selectedKey.value] || null : null));

/**
 * Auto-fits unbounded infinite-canvas coordinates into the embedded preview box
 * whenever items have been spread beyond [10..90] in the full-screen Studio.
 */
const previewViewport = computed(() => {
  if (nodes.value.length === 0) return { minX: 0, spanX: 100, minY: 0, spanY: 100, scaleFactor: 1 };
  let minX = Infinity;
  let maxX = -Infinity;
  let minY = Infinity;
  let maxY = -Infinity;
  for (const n of nodes.value) {
    minX = Math.min(minX, n.x);
    maxX = Math.max(maxX, n.x);
    minY = Math.min(minY, n.y);
    maxY = Math.max(maxY, n.y);
  }
  const outOfStandard = minX < 10 || maxX > 90 || minY < 10 || maxY > 90;
  if (!outOfStandard) {
    return { minX: 0, spanX: 100, minY: 0, spanY: 100, scaleFactor: 1 };
  }
  const padX = Math.max(18, (maxX - minX) * 0.22);
  const padY = Math.max(18, (maxY - minY) * 0.22);
  const span = Math.max(100, maxX - minX + padX * 2, maxY - minY + padY * 2);
  const cx = (minX + maxX) / 2;
  const cy = (minY + maxY) / 2;
  return {
    minX: cx - span / 2,
    spanX: span,
    minY: cy - span / 2,
    spanY: span,
    scaleFactor: Math.max(0.45, Math.min(1, 100 / span)),
  };
});

function toPreviewPct(node: CollageNode) {
  const vp = previewViewport.value;
  return {
    left: `${((node.x - vp.minX) / vp.spanX) * 100}%`,
    top: `${((node.y - vp.minY) / vp.spanY) * 100}%`,
    scale: node.scale * vp.scaleFactor,
  };
}

function onPointerDown(e: PointerEvent, node: CollageNode) {
  const rect = canvasRef.value?.getBoundingClientRect();
  if (!rect) return;
  selectedKey.value = node.nodeKey;
  (e.currentTarget as HTMLElement)?.setPointerCapture?.(e.pointerId);
  dragState.value = {
    nodeKey: node.nodeKey,
    startClientX: e.clientX,
    startClientY: e.clientY,
    origX: node.x,
    origY: node.y,
    canvasW: rect.width,
    canvasH: rect.height,
  };
}

function onPointerMove(e: PointerEvent) {
  const d = dragState.value;
  if (!d) return;
  const node = nodes.value.find((n) => n.nodeKey === d.nodeKey);
  if (!node) return;
  const vp = previewViewport.value;
  const dxUnits = ((e.clientX - d.startClientX) / d.canvasW) * vp.spanX;
  const dyUnits = ((e.clientY - d.startClientY) / d.canvasH) * vp.spanY;
  node.x = Number((d.origX + dxUnits).toFixed(1));
  node.y = Number((d.origY + dyUnits).toFixed(1));
  hasUnsavedChanges.value = true;
}

function onPointerUp() {
  dragState.value = null;
}

function adjustScale(delta: number) {
  if (!selectedNode.value) return;
  selectedNode.value.scale = Number(
    Math.max(0.4, Math.min(2.0, selectedNode.value.scale + delta)).toFixed(2)
  );
  hasUnsavedChanges.value = true;
}

function adjustRotation(delta: number) {
  if (!selectedNode.value) return;
  selectedNode.value.rotation = Math.max(-90, Math.min(90, selectedNode.value.rotation + delta));
  hasUnsavedChanges.value = true;
}

function setRotation(val: number) {
  if (!selectedNode.value) return;
  selectedNode.value.rotation = val;
  hasUnsavedChanges.value = true;
}

function toggleFlip() {
  if (!selectedNode.value) return;
  selectedNode.value.flipX = !selectedNode.value.flipX;
  hasUnsavedChanges.value = true;
}

function bringToFront() {
  if (!selectedNode.value) return;
  const maxZ = Math.max(...nodes.value.map((n) => n.zIndex), 1);
  selectedNode.value.zIndex = maxZ + 1;
  normalizeZ();
  hasUnsavedChanges.value = true;
}

function sendToBack() {
  if (!selectedNode.value) return;
  const minZ = Math.min(...nodes.value.map((n) => n.zIndex), 1);
  selectedNode.value.zIndex = minZ - 1;
  normalizeZ();
  hasUnsavedChanges.value = true;
}

function normalizeZ() {
  const sorted = [...nodes.value].sort((a, b) => a.zIndex - b.zIndex);
  sorted.forEach((n, idx) => {
    n.zIndex = idx + 1;
  });
}

function resetAutoLayout() {
  nodes.value = buildDefaultLayout(props.plan.slots || []);
  hasUnsavedChanges.value = true;
  toast.show('已重置为杂志全身平铺构图');
}

function handleSave() {
  emit('save-layout', nodes.value.map((n) => ({ ...n })));
  hasUnsavedChanges.value = false;
}

function removeSelectedNode() {
  const node = selectedNode.value;
  if (!node) return;
  if (node.labelText || !node.itemId || node.itemId <= 0) {
    nodes.value = nodes.value.filter((n) => n.nodeKey !== node.nodeKey);
    selectedKey.value = null;
    hasUnsavedChanges.value = true;
    toast.show('已从画板移除该元素');
    return;
  }
  const ownedCount = (props.plan.slots || []).filter((s) => !s.missing && s.item?.id > 0).length;
  if (ownedCount <= 1) {
    toast.show('搭配至少需保留 1 件衣橱单品');
    return;
  }
  const targetItemId = node.itemId;
  selectedKey.value = null;
  emit('remove-slot', targetItemId);
}

function onWindowKeydown(e: KeyboardEvent) {
  if (studioOpen.value || !selectedNode.value) return;
  if (e.key !== 'Delete' && e.key !== 'Backspace') return;
  const target = e.target as HTMLElement | null;
  if (
    target &&
    (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)
  ) {
    return;
  }
  e.preventDefault();
  removeSelectedNode();
}

onMounted(() => {
  window.addEventListener('keydown', onWindowKeydown);
});

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onWindowKeydown);
});

function handleStudioUpdateNodes(nextNodes: CollageNode[]) {
  nodes.value = nextNodes.map((n) => ({ ...n }));
  hasUnsavedChanges.value = true;
}

function handleStudioSave(nextNodes: CollageNode[]) {
  nodes.value = nextNodes.map((n) => ({ ...n }));
  emit('save-layout', nodes.value.map((n) => ({ ...n })));
  hasUnsavedChanges.value = false;
}

async function exportCollagePng() {
  if (!canvasRef.value || exporting.value) return;
  exporting.value = true;
  try {
    const outW = 1080;
    const outH = 1350;
    const stageH = 1140;
    const canvas = document.createElement('canvas');
    canvas.width = outW;
    canvas.height = outH;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('Canvas 不可用');

    ctx.fillStyle = '#FFFFFF';
    ctx.fillRect(0, 0, outW, outH);
    ctx.fillStyle = '#FAFAFA';
    ctx.fillRect(36, 36, outW - 72, stageH - 36);
    ctx.fillStyle = 'rgba(0,0,0,0.08)';
    for (let gx = 60; gx < outW - 50; gx += 32) {
      for (let gy = 60; gy < stageH - 20; gy += 32) {
        ctx.beginPath();
        ctx.arc(gx, gy, 1.3, 0, Math.PI * 2);
        ctx.fill();
      }
    }

    const ordered = [...nodes.value].sort((a, b) => a.zIndex - b.zIndex);
    for (const node of ordered) {
      const cx = (node.x / 100) * (outW - 72) + 36;
      const cy = (node.y / 100) * (stageH - 36) + 36;

      if (node.labelText) {
        ctx.save();
        ctx.translate(cx, cy);
        ctx.rotate((node.rotation * Math.PI) / 180);
        ctx.font = '600 28px Inter, "Noto Sans SC", sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillStyle = '#000000';
        ctx.fillText(node.labelText, 0, 0);
        ctx.restore();
        continue;
      }

      const domEl = canvasRef.value.querySelector(`[data-node-key="${CSS.escape(node.nodeKey)}"]`);
      if (!domEl) continue;

      const baseBox = 300 * node.scale;
      let imgEl: HTMLImageElement | null = null;
      const rawImg = domEl.querySelector('img') as HTMLImageElement | null;
      const rawSvg = domEl.querySelector('svg') as SVGSVGElement | null;

      if (rawImg && rawImg.src) {
        imgEl = await loadImageSafe(rawImg.src);
      } else if (rawSvg) {
        const cloned = rawSvg.cloneNode(true) as SVGSVGElement;
        cloned.setAttribute('xmlns', 'http://www.w3.org/www.w3.org/2000/svg');
        if (!cloned.getAttribute('width')) cloned.setAttribute('width', '320');
        if (!cloned.getAttribute('height')) cloned.setAttribute('height', '320');
        const svgStr = new XMLSerializer().serializeToString(cloned);
        const dataUrl = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svgStr);
        imgEl = await loadImageSafe(dataUrl);
      }

      if (imgEl) {
        ctx.save();
        ctx.translate(cx, cy);
        ctx.rotate((node.rotation * Math.PI) / 180);
        if (node.flipX) ctx.scale(-1, 1);
        const ratio = imgEl.naturalWidth && imgEl.naturalHeight ? imgEl.naturalWidth / imgEl.naturalHeight : 1;
        const drawW = ratio >= 1 ? baseBox : baseBox * ratio;
        const drawH = ratio >= 1 ? baseBox / ratio : baseBox;
        ctx.shadowColor = 'rgba(0,0,0,0.08)';
        ctx.shadowBlur = 18;
        ctx.shadowOffsetY = 8;
        ctx.drawImage(imgEl, -drawW / 2, -drawH / 2, drawW, drawH);
        ctx.restore();
      }
    }

    ctx.strokeStyle = 'rgba(0,0,0,0.08)';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(56, stageH + 30);
    ctx.lineTo(outW - 56, stageH + 30);
    ctx.stroke();

    ctx.fillStyle = '#000000';
    ctx.font = '600 34px Inter, "Noto Sans SC", sans-serif';
    ctx.fillText(props.plan.themeTitle || 'ALTA · 智搭衣橱 OOTD', 56, stageH + 92);

    ctx.fillStyle = '#666666';
    ctx.font = '400 22px Inter, "Noto Sans SC", sans-serif';
    const piecesSummary = nodes.value
      .filter((n) => !n.labelText)
      .map((n) => resolveNodeVisual(n).name)
      .slice(0, 4)
      .join(' · ');
    ctx.fillText(piecesSummary, 56, stageH + 134);

    ctx.fillStyle = '#000000';
    ctx.font = '500 20px "JetBrains Mono", monospace';
    const today = new Date().toISOString().slice(0, 10);
    ctx.fillText(`ALTA · 智搭   ${today}   MATCH ${props.plan.matchScore}%`, 56, stageH + 178);

    const a = document.createElement('a');
    a.href = canvas.toDataURL('image/png');
    a.download = `alta-ootd-${props.plan.id}.png`;
    a.click();
    toast.show('已导出 1080×1350 高清杂志平铺拼贴图');
  } catch (e: any) {
    toast.show(e.message || '导出拼贴图失败');
  } finally {
    exporting.value = false;
  }
}

function loadImageSafe(src: string): Promise<HTMLImageElement | null> {
  return new Promise((resolve) => {
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => resolve(img);
    img.onerror = () => resolve(null);
    img.src = src;
  });
}
</script>

<template>
  <div class="space-y-2.5">
    <!-- Embedded Preview Stage with Minimal Floating Top Controls -->
    <div
      ref="canvasRef"
      @click.self="selectedKey = null"
      @dblclick.self="openStudio"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
      class="group relative w-full h-[450px] sm:h-[530px] rounded-2xl border border-black/[0.06] bg-[#FAFAFA] overflow-hidden select-none touch-none"
      style="
        background-image: radial-gradient(rgba(0, 0, 0, 0.085) 1px, transparent 0);
        background-size: 22px 22px;
      "
    >
      <!-- Top-Left Minimal Floating Actions -->
      <div class="absolute top-3 left-3 z-[40] flex items-center gap-1 p-1 rounded-full bg-white/92 backdrop-blur-md border border-black/[0.08] shadow-sm text-[11.5px]">
        <button
          type="button"
          @click.stop="emit('open-add-item')"
          title="从衣橱添加单品到当前搭配"
          class="px-2.5 py-1 rounded-full bg-black text-white hover:bg-[#262626] font-medium transition inline-flex items-center gap-1"
        >
          <AppIcon name="plus" :size="11" />
          加单品
        </button>
        <button
          type="button"
          @click.stop="resetAutoLayout"
          title="自动排版"
          class="px-2.5 py-1 rounded-full hover:bg-[#F2F2F2] text-black font-medium transition"
        >
          重排
        </button>
        <button
          type="button"
          @click.stop="handleSave"
          :disabled="saving"
          title="保存排版"
          class="px-2.5 py-1 rounded-full font-medium transition inline-flex items-center gap-1"
          :class="
            hasUnsavedChanges
              ? 'bg-black text-white hover:bg-[#262626]'
              : 'hover:bg-[#F2F2F2] text-[#555555]'
          "
        >
          <AppIcon name="check" :size="12" />
          {{ saving ? '保存中' : hasUnsavedChanges ? '保存' : '已同步' }}
        </button>
        <button
          type="button"
          @click.stop="exportCollagePng"
          :disabled="exporting"
          title="导出 1080×1350 高清拼贴图"
          class="w-7 h-7 rounded-full hover:bg-[#F2F2F2] text-black flex items-center justify-center transition"
        >
          <AppIcon name="download" :size="13" />
        </button>
      </div>

      <!-- Top-Right Clean Full-Screen Studio Entry -->
      <button
        type="button"
        @click.stop="openStudio"
        class="absolute top-3 right-3 z-[40] px-3.5 py-1.5 rounded-full bg-black hover:bg-[#262626] text-[11.5px] font-medium text-white shadow-sm transition flex items-center gap-1.5"
      >
        <AppIcon name="maximize" :size="12" />
        全屏工作台
      </button>

      <!-- Subtle Drag Hint (Only when nothing is selected) -->
      <div
        v-if="!selectedNode"
        class="pointer-events-none absolute bottom-3 left-1/2 -translate-x-1/2 px-3 py-1 rounded-full bg-white/80 backdrop-blur-sm text-[11px] text-black/45 whitespace-nowrap"
      >
        拖拽单品自由排版 · 点击微调大小与层级
      </div>

      <!-- Draggable Garment & Stamp Nodes -->
      <div
        v-for="node in nodes"
        :key="node.nodeKey"
        :data-node-key="node.nodeKey"
        @pointerdown.stop="onPointerDown($event, node)"
        @click.stop="selectedKey = node.nodeKey"
        class="absolute cursor-grab active:cursor-grabbing transition-shadow"
        :style="{
          left: toPreviewPct(node).left,
          top: toPreviewPct(node).top,
          zIndex: node.zIndex,
          transform: `translate(-50%, -50%) scale(${toPreviewPct(node).scale}) rotate(${node.rotation}deg)`,
        }"
      >
        <div
          v-if="node.labelText"
          class="px-3 py-1 rounded-lg font-mono text-[13px] font-semibold tracking-[0.16em] text-black whitespace-nowrap"
          :class="selectedKey === node.nodeKey ? 'ring-2 ring-black bg-white/70' : ''"
        >
          {{ node.labelText }}
        </div>
        <div
          v-else
          class="relative w-36 h-36 sm:w-44 sm:h-44 p-2 rounded-2xl flex items-center justify-center transition"
          :class="
            selectedKey === node.nodeKey
              ? 'ring-2 ring-black bg-white/60 backdrop-blur-[1px] shadow-float'
              : 'hover:ring-1 hover:ring-black/25'
          "
        >
          <div
            class="w-full h-full flex items-center justify-center pointer-events-none"
            :style="{ transform: node.flipX ? 'scaleX(-1)' : 'none' }"
          >
            <GarmentVisual
              :image-url="resolveNodeVisual(node).imageUrl"
              :svg-type="resolveNodeVisual(node).svgType"
              :color-hex="resolveNodeVisual(node).colorHex"
              :alt="resolveNodeVisual(node).name"
              class="w-full h-full drop-shadow-[0_10px_16px_rgba(0,0,0,0.10)]"
            />
          </div>

          <span
            v-if="selectedKey === node.nodeKey"
            class="absolute -top-3 left-1/2 -translate-x-1/2 px-2 py-0.5 rounded-full bg-black text-white text-[10px] font-medium whitespace-nowrap shadow-sm"
          >
            {{ resolveNodeVisual(node).name }} · 层 {{ node.zIndex }}
          </span>
          <span
            v-else-if="slotMap[node.nodeKey]?.missing"
            class="absolute -bottom-1 left-1/2 -translate-x-1/2 px-2 py-0.5 rounded-full bg-white/95 border border-dashed border-black/30 text-[9.5px] text-black whitespace-nowrap"
          >
            建议补全
          </span>
        </div>
      </div>
    </div>

    <!-- Compact Inspector Dock below preview -->
    <div
      v-if="selectedNode"
      class="rounded-2xl bg-black text-white px-4 py-2.5 shadow-card flex flex-wrap items-center justify-between gap-2.5 text-[11.5px]"
    >
      <div class="flex flex-wrap items-center gap-3">
        <div class="flex items-center gap-1.5">
          <span class="text-white/60 text-[11px]">缩放</span>
          <button
            type="button"
            @click="adjustScale(-0.12)"
            class="w-6 h-6 rounded-full bg-white/15 hover:bg-white/25 flex items-center justify-center font-mono"
          >
            −
          </button>
          <span class="font-mono w-10 text-center">{{ Math.round(selectedNode.scale * 100) }}%</span>
          <button
            type="button"
            @click="adjustScale(0.12)"
            class="w-6 h-6 rounded-full bg-white/15 hover:bg-white/25 flex items-center justify-center font-mono"
          >
            +
          </button>
        </div>

        <div class="flex items-center gap-1.5 pl-2.5 border-l border-white/15">
          <span class="text-white/60 text-[11px]">角度</span>
          <button
            type="button"
            @click="adjustRotation(-5)"
            class="px-2 h-6 rounded-full bg-white/15 hover:bg-white/25 font-mono text-[10.5px]"
          >
            -5°
          </button>
          <button
            type="button"
            @click="setRotation(0)"
            class="px-2 h-6 rounded-full bg-white/10 hover:bg-white/25 font-mono text-[10.5px]"
          >
            {{ selectedNode.rotation }}°
          </button>
          <button
            type="button"
            @click="adjustRotation(5)"
            class="px-2 h-6 rounded-full bg-white/15 hover:bg-white/25 font-mono text-[10.5px]"
          >
            +5°
          </button>
        </div>

        <div class="flex items-center gap-1 pl-2.5 border-l border-white/15">
          <button
            type="button"
            @click="toggleFlip"
            class="px-2.5 h-6 rounded-full bg-white/15 hover:bg-white text-white hover:text-black transition"
          >
            镜像
          </button>
          <button
            type="button"
            @click="bringToFront"
            class="px-2.5 h-6 rounded-full bg-white/15 hover:bg-white text-white hover:text-black transition"
          >
            置顶
          </button>
          <button
            type="button"
            @click="sendToBack"
            class="px-2.5 h-6 rounded-full bg-white/15 hover:bg-white text-white hover:text-black transition"
          >
            置底
          </button>
        </div>
      </div>

      <div class="flex items-center gap-1.5 shrink-0">
        <button
          v-if="selectedSlot && !selectedSlot.anchor"
          type="button"
          @click="emit('swap-slot', selectedSlot.slotKey, selectedSlot.item.id)"
          class="px-3 h-7 rounded-full bg-white text-black hover:bg-white/90 font-medium flex items-center gap-1"
        >
          <AppIcon name="refresh" :size="11" />
          换一件
        </button>
        <button
          v-if="selectedNode"
          type="button"
          @click="removeSelectedNode"
          title="从当前搭配移除此单品 (Delete)"
          class="px-3 h-7 rounded-full bg-white/15 hover:bg-red-500 text-white font-medium flex items-center gap-1 transition"
        >
          <AppIcon name="trash" :size="11" />
          移除
        </button>
      </div>
    </div>

    <!-- Full-Viewport Standalone Flat-Lay Studio Modal -->
    <FlatLayStudioModal
      :open="studioOpen"
      :plan="plan"
      :initial-nodes="nodes"
      :saving="saving"
      @close="closeStudio"
      @update-nodes="handleStudioUpdateNodes"
      @save-layout="handleStudioSave"
      @swap-slot="(slotKey, id) => emit('swap-slot', slotKey, id)"
    />
  </div>
</template>
