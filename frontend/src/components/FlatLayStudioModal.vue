<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { CollageNode, OutfitPlanVo, PlanSlot, WardrobeItem } from '@/types';
import { useWardrobeStore } from '@/stores/wardrobe';
import { useToastStore } from '@/stores/toast';
import { categoryLabel } from '@/utils/constants';
import AppIcon from './AppIcon.vue';
import GarmentVisual from './GarmentVisual.vue';

const props = defineProps<{
  open: boolean;
  plan: OutfitPlanVo;
  initialNodes: CollageNode[];
  saving?: boolean;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'update-nodes', nodes: CollageNode[]): void;
  (e: 'save-layout', nodes: CollageNode[]): void;
  (e: 'swap-slot', slotKey: string, currentItemId: number): void;
}>();

const wardrobeStore = useWardrobeStore();
const toast = useToastStore();

/**
 * Spatial Coordinate System (Infinite 2D World):
 * Node (x, y) are unbounded numbers where 1 unit = WORLD_SCALE_PX (12px).
 * Legacy [0..100] coordinates naturally span a generous 1200x1200px central stage
 * around (50, 50), with ZERO clipping walls — items can be placed at (-150, 280), etc.
 */
const WORLD_UNIT_PX = 12;
const GARMENT_BASE_PX = 240;

function nodeToWorldPx(n: CollageNode) {
  return {
    wx: (n.x - 50) * WORLD_UNIT_PX,
    wy: (n.y - 50) * WORLD_UNIT_PX,
  };
}

function worldPxToNodeCoord(wx: number, wy: number) {
  return {
    x: Number((wx / WORLD_UNIT_PX + 50).toFixed(2)),
    y: Number((wy / WORLD_UNIT_PX + 50).toFixed(2)),
  };
}

// Studio UI & Camera state
const viewportRef = ref<HTMLElement | null>(null);
const drawerTab = ref<'wardrobe' | 'accessories' | 'text'>('wardrobe');
const searchKeyword = ref('');
const activeCategory = ref<string>('all');
const leftOpen = ref(true);
const layersOpen = ref(false);
const guideRatio = ref<'free' | '16:9' | '4:5' | '1:1'>('free');
const backdropId = ref<'dot' | 'white' | 'oat' | 'stone' | 'noir'>('dot');
const transparentExport = ref(false);
const customStampInput = ref('');
const exporting = ref(false);
const hasUnsavedChanges = ref(false);
const spacePressed = ref(false);

// Infinite Canvas Camera: ScreenPos = ViewportCenter + pan + WorldPos * zoom
const panX = ref(0);
const panY = ref(0);
const zoom = ref(1);

const nodes = ref<CollageNode[]>([]);
const selectedKey = ref<string | null>(null);

// Magnetic snap guides in World Pixels
const snapWorldX = ref<number | null>(null);
const snapWorldY = ref<number | null>(null);

// Undo / Redo stack
const historyStack = ref<CollageNode[][]>([]);
const historyIndex = ref(-1);

const CATEGORY_FILTERS = [
  { key: 'all', label: '全部' },
  { key: 'outerwear', label: '外套' },
  { key: 'top', label: '上装' },
  { key: 'bottom', label: '下装' },
  { key: 'dress', label: '连衣裙' },
  { key: 'shoes', label: '鞋履' },
  { key: 'accessory', label: '配饰' },
];

const STUDIO_ACCESSORIES = [
  { id: 'acc-ear-hoop', name: '雕塑水滴镀金耳环', category: 'accessory', imageUrl: '/alta/pieces/gold-hoop-earrings.webp', colorHex: '#D4AF37' },
  { id: 'acc-ear-pearl', name: '巴洛克珍珠复古耳钉', category: 'accessory', imageUrl: '/alta/pieces/gold-pearl-earrings.webp', colorHex: '#E8DFCA' },
  { id: 'acc-neck-clover', name: '极简四叶草锁骨链', category: 'accessory', imageUrl: '/alta/pieces/gold-clover-necklace.webp', colorHex: '#D4AF37' },
  { id: 'acc-bangle-knot', name: '打结雕塑感金手镯', category: 'accessory', imageUrl: '/alta/pieces/gold-knot-bangle.webp', colorHex: '#D4AF37' },
  { id: 'acc-watch-gold', name: '复古小方盘黑金女表', category: 'accessory', imageUrl: '/alta/pieces/gold-watch.webp', colorHex: '#2B2B2B' },
  { id: 'acc-watch-silver', name: '经典精钢银盘腕表', category: 'accessory', imageUrl: '/alta/pieces/seiko-watch.webp', colorHex: '#C0C0C0' },
  { id: 'acc-glasses', name: '极简椭圆黑框墨镜', category: 'accessory', imageUrl: '/alta/pieces/black-sunglasses.webp', colorHex: '#1A1A1A' },
  { id: 'acc-scarf', name: '法式撞色桑蚕丝方巾', category: 'accessory', imageUrl: '/alta/pieces/silk-scarf.webp', colorHex: '#8E352E' },
  { id: 'acc-bag-crescent', name: '大象灰真皮月牙腋下包', category: 'accessory', imageUrl: '/alta/pieces/taupe-crescent-bag.webp', colorHex: '#B2A89F' },
  { id: 'acc-bag-tote', name: '奶油白荔枝纹托特包', category: 'accessory', imageUrl: '/alta/pieces/cream-tote.webp', colorHex: '#F3EFE6' },
  { id: 'acc-bag-red', name: '勃艮第红麂皮单肩包', category: 'accessory', imageUrl: '/alta/pieces/red-suede-bag.webp', colorHex: '#7A2326' },
  { id: 'acc-shawl', name: '软糯针织披肩外搭', category: 'accessory', imageUrl: '/alta/pieces/sweater-shawl.webp', colorHex: '#E5DEC9' },
];

const TEXT_PRESETS = [
  'OOTD · LOOKBOOK',
  'MINIMALIST CAPSULE',
  'EFFORTLESS CHIC',
  'AUTUMN / WINTER',
  'ALTA ARCHIVE · 2026',
  'URBAN COMMUTE',
];

const BACKDROPS = [
  { id: 'dot' as const, label: '点阵画布', bg: '#FAFAFA', dot: true, dark: false },
  { id: 'white' as const, label: '纯白画廊', bg: '#FFFFFF', dot: false, dark: false },
  { id: 'oat' as const, label: '暖燕麦纸', bg: '#F5F2EB', dot: false, dark: false },
  { id: 'stone' as const, label: '买手微灰', bg: '#ECECEC', dot: false, dark: false },
  { id: 'noir' as const, label: '暗房黑底', bg: '#141414', dot: false, dark: true },
];

const activeBackdrop = computed(() => BACKDROPS.find((b) => b.id === backdropId.value) || BACKDROPS[0]);

const guideFrameBox = computed(() => {
  if (guideRatio.value === '16:9') return { w: 1280, h: 720, label: '16:9 横屏大片取景框' };
  if (guideRatio.value === '4:5') return { w: 800, h: 1000, label: '4:5 小红书画报取景框' };
  if (guideRatio.value === '1:1') return { w: 900, h: 900, label: '1:1 极简方画幅取景框' };
  return null;
});

function cloneNodes(list: CollageNode[]): CollageNode[] {
  return list.map((n) => ({ ...n }));
}

function pushHistory(snapshot: CollageNode[], markUnsaved = true) {
  const trimmed = historyStack.value.slice(0, historyIndex.value + 1);
  trimmed.push(cloneNodes(snapshot));
  if (trimmed.length > 40) trimmed.shift();
  historyStack.value = trimmed;
  historyIndex.value = trimmed.length - 1;
  if (markUnsaved) {
    hasUnsavedChanges.value = true;
    emit('update-nodes', cloneNodes(nodes.value));
  }
}

const canUndo = computed(() => historyIndex.value > 0);
const canRedo = computed(() => historyIndex.value < historyStack.value.length - 1);

function undo() {
  if (!canUndo.value) return;
  historyIndex.value -= 1;
  nodes.value = cloneNodes(historyStack.value[historyIndex.value]);
  hasUnsavedChanges.value = true;
  emit('update-nodes', cloneNodes(nodes.value));
}

function redo() {
  if (!canRedo.value) return;
  historyIndex.value += 1;
  nodes.value = cloneNodes(historyStack.value[historyIndex.value]);
  hasUnsavedChanges.value = true;
  emit('update-nodes', cloneNodes(nodes.value));
}

/**
 * Calculates world bounding box of all active items and smoothly centers + scales
 * the camera to fill the user's actual screen (accounting for floating left drawer).
 */
function fitAllToViewport() {
  const el = viewportRef.value;
  if (!el || nodes.value.length === 0) {
    panX.value = leftOpen.value ? 120 : 0;
    panY.value = 0;
    zoom.value = 1;
    return;
  }
  const vw = el.clientWidth;
  const vh = el.clientHeight;
  const leftInset = leftOpen.value && vw >= 768 ? 296 : 24;
  const rightInset = layersOpen.value && vw >= 768 ? 276 : 24;
  const topInset = 84;
  const bottomInset = 68;

  const availW = Math.max(320, vw - leftInset - rightInset);
  const availH = Math.max(280, vh - topInset - bottomInset);

  let minX = Infinity;
  let maxX = -Infinity;
  let minY = Infinity;
  let maxY = -Infinity;

  for (const n of nodes.value) {
    const { wx, wy } = nodeToWorldPx(n);
    const half = n.labelText ? 90 * n.scale : (GARMENT_BASE_PX * n.scale) / 2;
    minX = Math.min(minX, wx - half);
    maxX = Math.max(maxX, wx + half);
    minY = Math.min(minY, wy - half);
    maxY = Math.max(maxY, wy + half);
  }

  const contentW = Math.max(360, maxX - minX + 120);
  const contentH = Math.max(360, maxY - minY + 120);
  const nextZoom = Math.max(0.45, Math.min(1.45, Math.min(availW / contentW, availH / contentH)));

  const centerWX = (minX + maxX) / 2;
  const centerWY = (minY + maxY) / 2;
  const targetScreenCenterX = leftInset + availW / 2 - vw / 2;
  const targetScreenCenterY = topInset + availH / 2 - vh / 2;

  zoom.value = Number(nextZoom.toFixed(2));
  panX.value = Math.round(targetScreenCenterX - centerWX * zoom.value);
  panY.value = Math.round(targetScreenCenterY - centerWY * zoom.value);
}

watch(
  () => [props.open, props.plan.id],
  async ([isOpen]) => {
    if (isOpen) {
      nodes.value = cloneNodes(props.initialNodes);
      historyStack.value = [cloneNodes(props.initialNodes)];
      historyIndex.value = 0;
      hasUnsavedChanges.value = false;
      selectedKey.value = null;
      document.body.style.overflow = 'hidden';
      await nextTick();
      fitAllToViewport();
    } else {
      document.body.style.overflow = '';
    }
  },
  { immediate: true }
);

watch(
  () => props.initialNodes,
  (next) => {
    if (props.open && !hasUnsavedChanges.value) {
      nodes.value = cloneNodes(next);
      historyStack.value = [cloneNodes(next)];
      historyIndex.value = 0;
    }
  }
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

const slotByKey = computed(() => {
  const map: Record<string, PlanSlot> = {};
  (props.plan.slots || []).forEach((s, idx) => {
    map[`${s.slotKey}-${s.item.id}-${idx}`] = s;
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
    return {
      isText: true,
      name: node.labelText,
      imageUrl: '',
      svgType: undefined,
      colorHex: '#000000',
      slot: null as PlanSlot | null,
    };
  }
  const slot = slotByKey.value[node.nodeKey] || null;
  if (slot) {
    return {
      isText: false,
      name: slot.item.name,
      imageUrl: slot.item.processedImageUrl,
      svgType: slot.item.svgType,
      colorHex: slot.item.colorHex,
      slot,
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
      slot: null,
    };
  }
  return {
    isText: false,
    name: node.customName || '时尚单品',
    imageUrl: node.customImageUrl,
    svgType: node.customSvgType,
    colorHex: node.customColorHex || '#EFECE6',
    slot: null,
  };
}

const selectedNode = computed(() => nodes.value.find((n) => n.nodeKey === selectedKey.value) || null);
const selectedVisual = computed(() => (selectedNode.value ? resolveNodeVisual(selectedNode.value) : null));
const orderedLayersDesc = computed(() => [...nodes.value].sort((a, b) => b.zIndex - a.zIndex));
const placedItemIds = computed(() => new Set(nodes.value.filter((n) => n.itemId > 0).map((n) => n.itemId)));

const filteredWardrobe = computed(() => {
  const kw = searchKeyword.value.trim().toLowerCase();
  return wardrobeStore.items.filter((item) => {
    if (activeCategory.value !== 'all' && item.category !== activeCategory.value) return false;
    if (!kw) return true;
    return (
      item.name.toLowerCase().includes(kw) ||
      item.color.toLowerCase().includes(kw) ||
      (item.subCategory || '').toLowerCase().includes(kw)
    );
  });
});

// Unified Pointer Interaction: pan | move | scale | rotate
interface InteractionState {
  mode: 'pan' | 'move' | 'scale' | 'rotate';
  nodeKey: string;
  startClientX: number;
  startClientY: number;
  origPanX: number;
  origPanY: number;
  origWX: number;
  origWY: number;
  origScale: number;
  origRotation: number;
  centerScreenX: number;
  centerScreenY: number;
  startDist: number;
  startAngleDeg: number;
  moved: boolean;
}
const interaction = ref<InteractionState | null>(null);

function getNodeScreenCenter(node: CollageNode) {
  const rect = viewportRef.value?.getBoundingClientRect();
  const { wx, wy } = nodeToWorldPx(node);
  const cx = (rect ? rect.left + rect.width / 2 : window.innerWidth / 2) + panX.value + wx * zoom.value;
  const cy = (rect ? rect.top + rect.height / 2 : window.innerHeight / 2) + panY.value + wy * zoom.value;
  return { cx, cy };
}

function onViewportPointerDown(e: PointerEvent) {
  // Clicking empty background or holding Space / middle button pans the infinite canvas
  if (e.button !== 0 && e.button !== 1) return;
  (e.currentTarget as HTMLElement)?.setPointerCapture?.(e.pointerId);
  interaction.value = {
    mode: 'pan',
    nodeKey: '',
    startClientX: e.clientX,
    startClientY: e.clientY,
    origPanX: panX.value,
    origPanY: panY.value,
    origWX: 0,
    origWY: 0,
    origScale: 1,
    origRotation: 0,
    centerScreenX: 0,
    centerScreenY: 0,
    startDist: 1,
    startAngleDeg: 0,
    moved: false,
  };
}

function startMoveNode(e: PointerEvent, node: CollageNode) {
  if (spacePressed.value || e.button === 1) {
    onViewportPointerDown(e);
    return;
  }
  selectedKey.value = node.nodeKey;
  (e.currentTarget as HTMLElement)?.setPointerCapture?.(e.pointerId);
  const { wx, wy } = nodeToWorldPx(node);
  interaction.value = {
    mode: 'move',
    nodeKey: node.nodeKey,
    startClientX: e.clientX,
    startClientY: e.clientY,
    origPanX: panX.value,
    origPanY: panY.value,
    origWX: wx,
    origWY: wy,
    origScale: node.scale,
    origRotation: node.rotation,
    centerScreenX: 0,
    centerScreenY: 0,
    startDist: 1,
    startAngleDeg: 0,
    moved: false,
  };
}

function startScaleHandle(e: PointerEvent, node: CollageNode) {
  selectedKey.value = node.nodeKey;
  (e.currentTarget as HTMLElement)?.setPointerCapture?.(e.pointerId);
  const { cx, cy } = getNodeScreenCenter(node);
  const dist = Math.max(20, Math.hypot(e.clientX - cx, e.clientY - cy));
  interaction.value = {
    mode: 'scale',
    nodeKey: node.nodeKey,
    startClientX: e.clientX,
    startClientY: e.clientY,
    origPanX: 0,
    origPanY: 0,
    origWX: 0,
    origWY: 0,
    origScale: node.scale,
    origRotation: node.rotation,
    centerScreenX: cx,
    centerScreenY: cy,
    startDist: dist,
    startAngleDeg: 0,
    moved: false,
  };
}

function startRotateHandle(e: PointerEvent, node: CollageNode) {
  selectedKey.value = node.nodeKey;
  (e.currentTarget as HTMLElement)?.setPointerCapture?.(e.pointerId);
  const { cx, cy } = getNodeScreenCenter(node);
  const angle = (Math.atan2(e.clientY - cy, e.clientX - cx) * 180) / Math.PI;
  interaction.value = {
    mode: 'rotate',
    nodeKey: node.nodeKey,
    startClientX: e.clientX,
    startClientY: e.clientY,
    origPanX: 0,
    origPanY: 0,
    origWX: 0,
    origWY: 0,
    origScale: node.scale,
    origRotation: node.rotation,
    centerScreenX: cx,
    centerScreenY: cy,
    startDist: 1,
    startAngleDeg: angle,
    moved: false,
  };
}

function onGlobalPointerMove(e: PointerEvent) {
  const st = interaction.value;
  if (!st) return;

  if (st.mode === 'pan') {
    const dx = e.clientX - st.startClientX;
    const dy = e.clientY - st.startClientY;
    if (Math.abs(dx) > 2 || Math.abs(dy) > 2) st.moved = true;
    panX.value = Math.round(st.origPanX + dx);
    panY.value = Math.round(st.origPanY + dy);
    return;
  }

  const node = nodes.value.find((n) => n.nodeKey === st.nodeKey);
  if (!node) return;

  if (st.mode === 'move') {
    // True unbounded infinite world coordinates — zero walls!
    let nextWX = st.origWX + (e.clientX - st.startClientX) / zoom.value;
    let nextWY = st.origWY + (e.clientY - st.startClientY) / zoom.value;

    snapWorldX.value = null;
    snapWorldY.value = null;
    if (!e.shiftKey) {
      const snapThreshold = 10 / zoom.value;
      const xTargets = [0, ...nodes.value.filter((n) => n.nodeKey !== node.nodeKey).map((n) => nodeToWorldPx(n).wx)];
      const yTargets = [0, ...nodes.value.filter((n) => n.nodeKey !== node.nodeKey).map((n) => nodeToWorldPx(n).wy)];
      for (const tx of xTargets) {
        if (Math.abs(nextWX - tx) < snapThreshold) {
          nextWX = tx;
          snapWorldX.value = tx;
          break;
        }
      }
      for (const ty of yTargets) {
        if (Math.abs(nextWY - ty) < snapThreshold) {
          nextWY = ty;
          snapWorldY.value = ty;
          break;
        }
      }
    }

    const mapped = worldPxToNodeCoord(nextWX, nextWY);
    node.x = mapped.x;
    node.y = mapped.y;
    st.moved = true;
  } else if (st.mode === 'scale') {
    const dist = Math.max(16, Math.hypot(e.clientX - st.centerScreenX, e.clientY - st.centerScreenY));
    const ratio = dist / st.startDist;
    node.scale = Number(Math.max(0.3, Math.min(3.0, st.origScale * ratio)).toFixed(2));
    st.moved = true;
  } else if (st.mode === 'rotate') {
    const angle = (Math.atan2(e.clientY - st.centerScreenY, e.clientX - st.centerScreenX) * 180) / Math.PI;
    let deg = Math.round(st.origRotation + (angle - st.startAngleDeg));
    if (deg > 180) deg -= 360;
    if (deg < -180) deg += 360;
    if (!e.shiftKey && Math.abs(deg) < 4) deg = 0;
    node.rotation = deg;
    st.moved = true;
  }
}

function onGlobalPointerUp() {
  const st = interaction.value;
  if (!st) return;
  if (st.mode === 'pan' && !st.moved) {
    selectedKey.value = null;
  } else if (st.mode !== 'pan' && st.moved) {
    pushHistory(nodes.value);
  }
  interaction.value = null;
  snapWorldX.value = null;
  snapWorldY.value = null;
}

/**
 * Industry-standard Cursor-Anchored Zoom (Figma / tldraw / Excalidraw math):
 * Keeps the exact world point under the cursor stationary while zooming.
 */
function onViewportWheel(e: WheelEvent) {
  const rect = viewportRef.value?.getBoundingClientRect();
  if (!rect) return;
  e.preventDefault();

  // Cursor offset relative to viewport center
  const sx = e.clientX - (rect.left + rect.width / 2);
  const sy = e.clientY - (rect.top + rect.height / 2);

  // World point currently under cursor
  const wx = (sx - panX.value) / zoom.value;
  const wy = (sy - panY.value) / zoom.value;

  const factor = e.deltaY < 0 ? 1.09 : 1 / 1.09;
  const nextZoom = Number(Math.max(0.3, Math.min(2.8, zoom.value * factor)).toFixed(3));

  panX.value = Math.round(sx - wx * nextZoom);
  panY.value = Math.round(sy - wy * nextZoom);
  zoom.value = nextZoom;
}

function stepZoom(delta: number) {
  const nextZoom = Number(Math.max(0.3, Math.min(2.8, zoom.value + delta)).toFixed(2));
  const ratio = nextZoom / zoom.value;
  panX.value = Math.round(panX.value * ratio);
  panY.value = Math.round(panY.value * ratio);
  zoom.value = nextZoom;
}

// Compute current viewport center in world coordinates so newly added items drop right where user is looking
function getVisibleWorldCenter() {
  const el = viewportRef.value;
  const leftBias = leftOpen.value && el && el.clientWidth >= 768 ? 130 : 0;
  const wx = (leftBias - panX.value) / zoom.value;
  const wy = -panY.value / zoom.value;
  return worldPxToNodeCoord(wx, wy);
}

function addWardrobePiece(item: WardrobeItem) {
  const maxZ = Math.max(0, ...nodes.value.map((n) => n.zIndex));
  const center = getVisibleWorldCenter();
  const jitter = (nodes.value.length % 5) * 4 - 8;
  const newNode: CollageNode = {
    nodeKey: `wardrobe-${item.id}-${Date.now()}`,
    slotKey: item.category || 'extra',
    itemId: item.id,
    x: Number((center.x + jitter).toFixed(1)),
    y: Number((center.y + jitter).toFixed(1)),
    scale: item.category === 'accessory' ? 0.88 : 1.08,
    rotation: 0,
    zIndex: maxZ + 1,
    flipX: false,
    customName: item.name,
    customImageUrl: item.processedImageUrl,
    customSvgType: item.svgType,
    customColorHex: item.colorHex,
  };
  nodes.value.push(newNode);
  selectedKey.value = newNode.nodeKey;
  pushHistory(nodes.value);
  toast.show(`已将「${item.name}」放入画布`);
}

function addStudioAccessory(acc: (typeof STUDIO_ACCESSORIES)[number]) {
  const maxZ = Math.max(0, ...nodes.value.map((n) => n.zIndex));
  const center = getVisibleWorldCenter();
  const jitter = (nodes.value.length % 4) * 5 - 6;
  const newNode: CollageNode = {
    nodeKey: `${acc.id}-${Date.now()}`,
    slotKey: 'accessory',
    itemId: 0,
    x: Number((center.x + 12 + jitter).toFixed(1)),
    y: Number((center.y - 6 + jitter).toFixed(1)),
    scale: 0.85,
    rotation: 4,
    zIndex: maxZ + 1,
    flipX: false,
    customName: acc.name,
    customImageUrl: acc.imageUrl,
    customColorHex: acc.colorHex,
  };
  nodes.value.push(newNode);
  selectedKey.value = newNode.nodeKey;
  pushHistory(nodes.value);
  toast.show(`已添加配饰「${acc.name}」`);
}

function addTextStamp(text: string) {
  const clean = text.trim();
  if (!clean) return;
  const maxZ = Math.max(0, ...nodes.value.map((n) => n.zIndex));
  const center = getVisibleWorldCenter();
  const newNode: CollageNode = {
    nodeKey: `text-${Date.now()}`,
    slotKey: 'label',
    itemId: 0,
    x: center.x,
    y: Number((center.y - 26).toFixed(1)),
    scale: 1,
    rotation: 0,
    zIndex: maxZ + 1,
    labelText: clean,
    customName: clean,
  };
  nodes.value.push(newNode);
  selectedKey.value = newNode.nodeKey;
  customStampInput.value = '';
  pushHistory(nodes.value);
}

// Contextual selection actions
function toggleFlipX() {
  if (!selectedNode.value) return;
  selectedNode.value.flipX = !selectedNode.value.flipX;
  pushHistory(nodes.value);
}

function duplicateSelected() {
  const src = selectedNode.value;
  if (!src) return;
  const maxZ = Math.max(0, ...nodes.value.map((n) => n.zIndex));
  const copy: CollageNode = {
    ...src,
    nodeKey: `${src.slotKey}-copy-${Date.now()}`,
    x: Number((src.x + 10).toFixed(1)),
    y: Number((src.y + 8).toFixed(1)),
    zIndex: maxZ + 1,
  };
  nodes.value.push(copy);
  selectedKey.value = copy.nodeKey;
  pushHistory(nodes.value);
}

function removeNode(nodeKey: string) {
  if (nodes.value.length <= 1) {
    toast.show('画布上至少保留一件单品');
    return;
  }
  nodes.value = nodes.value.filter((n) => n.nodeKey !== nodeKey);
  if (selectedKey.value === nodeKey) {
    selectedKey.value = null;
  }
  normalizeZ();
  pushHistory(nodes.value);
}

function bringToFront(node: CollageNode) {
  const maxZ = Math.max(1, ...nodes.value.map((n) => n.zIndex));
  node.zIndex = maxZ + 1;
  normalizeZ();
  pushHistory(nodes.value);
}

function sendToBack(node: CollageNode) {
  const minZ = Math.min(1, ...nodes.value.map((n) => n.zIndex));
  node.zIndex = minZ - 1;
  normalizeZ();
  pushHistory(nodes.value);
}

function moveLayer(node: CollageNode, delta: number) {
  const sorted = [...nodes.value].sort((a, b) => a.zIndex - b.zIndex);
  const idx = sorted.findIndex((n) => n.nodeKey === node.nodeKey);
  const targetIdx = idx + delta;
  if (idx < 0 || targetIdx < 0 || targetIdx >= sorted.length) return;
  const tmp = sorted[idx].zIndex;
  sorted[idx].zIndex = sorted[targetIdx].zIndex;
  sorted[targetIdx].zIndex = tmp;
  normalizeZ();
  pushHistory(nodes.value);
}

function normalizeZ() {
  const sorted = [...nodes.value].sort((a, b) => a.zIndex - b.zIndex);
  sorted.forEach((n, i) => {
    n.zIndex = i + 1;
  });
}

// Spacious Wide-Spread Studio Layout Presets (uses generous horizontal space!)
async function applyLayoutPreset(preset: 'editorial' | 'wide' | 'scattered') {
  const garments = nodes.value.filter((n) => !n.labelText);
  if (preset === 'editorial') {
    const catCoords: Record<string, { x: number; y: number; scale: number; rot: number; z: number }> = {
      outerwear: { x: 24, y: 38, scale: 1.28, rot: -4, z: 4 },
      top: { x: 54, y: 26, scale: 1.12, rot: 2, z: 2 },
      dress: { x: 40, y: 48, scale: 1.32, rot: -2, z: 3 },
      bottom: { x: 52, y: 68, scale: 1.24, rot: -1, z: 3 },
      shoes: { x: 82, y: 74, scale: 1.0, rot: 5, z: 5 },
      accessory: { x: 84, y: 34, scale: 0.92, rot: 7, z: 6 },
    };
    const seen: Record<string, number> = {};
    garments.forEach((n, idx) => {
      const c = catCoords[n.slotKey] || { x: 25 + (idx % 3) * 28, y: 30 + Math.floor(idx / 3) * 32, scale: 1.05, rot: 0, z: idx + 1 };
      const count = seen[n.slotKey] || 0;
      seen[n.slotKey] = count + 1;
      n.x = c.x + count * 16;
      n.y = c.y + count * 12;
      n.scale = c.scale;
      n.rotation = c.rot;
      n.zIndex = c.z + count;
    });
  } else if (preset === 'wide') {
    // Horizontal showroom rail across wide desktop monitor
    const cols = Math.min(5, Math.max(3, garments.length));
    const spacingX = 28;
    const startX = 50 - ((cols - 1) * spacingX) / 2;
    garments.forEach((n, idx) => {
      const col = idx % cols;
      const row = Math.floor(idx / cols);
      n.x = Number((startX + col * spacingX).toFixed(1));
      n.y = Number((44 + row * 34).toFixed(1));
      n.scale = 1.12;
      n.rotation = 0;
      n.zIndex = idx + 1;
    });
  } else {
    garments.forEach((n, idx) => {
      const angle = (idx / Math.max(1, garments.length)) * Math.PI * 2;
      const rx = idx === 0 ? 0 : 34;
      const ry = idx === 0 ? 0 : 28;
      n.x = Number((50 + Math.cos(angle) * rx).toFixed(1));
      n.y = Number((50 + Math.sin(angle) * ry).toFixed(1));
      n.scale = idx === 0 ? 1.28 : 1.02;
      n.rotation = idx === 0 ? -2 : idx % 2 === 0 ? -6 : 6;
      n.zIndex = idx === 0 ? garments.length : idx;
    });
  }
  normalizeZ();
  pushHistory(nodes.value);
  await nextTick();
  fitAllToViewport();
}

function handleSave() {
  emit('save-layout', cloneNodes(nodes.value));
  hasUnsavedChanges.value = false;
}

function onKeyDown(e: KeyboardEvent) {
  if (!props.open) return;
  const tag = (e.target as HTMLElement | null)?.tagName?.toLowerCase();
  if (tag === 'input' || tag === 'textarea' || tag === 'select') return;

  if (e.code === 'Space') {
    spacePressed.value = true;
  }
  if (e.key === 'Escape') {
    if (selectedKey.value) {
      selectedKey.value = null;
    } else {
      emit('close');
    }
    return;
  }
  if (e.shiftKey && e.key === '1') {
    e.preventDefault();
    fitAllToViewport();
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
  if ((e.key === 'Delete' || e.key === 'Backspace') && selectedKey.value) {
    e.preventDefault();
    removeNode(selectedKey.value);
    return;
  }
  if (selectedNode.value && ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.key)) {
    e.preventDefault();
    const step = e.shiftKey ? 3 : 0.8;
    if (e.key === 'ArrowLeft') selectedNode.value.x = Number((selectedNode.value.x - step).toFixed(1));
    if (e.key === 'ArrowRight') selectedNode.value.x = Number((selectedNode.value.x + step).toFixed(1));
    if (e.key === 'ArrowUp') selectedNode.value.y = Number((selectedNode.value.y - step).toFixed(1));
    if (e.key === 'ArrowDown') selectedNode.value.y = Number((selectedNode.value.y + step).toFixed(1));
    pushHistory(nodes.value);
  }
}

function onKeyUp(e: KeyboardEvent) {
  if (e.code === 'Space') {
    spacePressed.value = false;
  }
}

/**
 * Excalidraw / tldraw Bounding-Box Smart Export:
 * Exports either the active guide frame OR the tight bounding box of all items across
 * the infinite canvas at 2x Retina resolution, with optional transparent background!
 */
async function exportStudioPng() {
  if (!viewportRef.value || exporting.value) return;
  exporting.value = true;
  try {
    let minWX = Infinity;
    let maxWX = -Infinity;
    let minWY = Infinity;
    let maxWY = -Infinity;

    if (guideFrameBox.value) {
      minWX = -guideFrameBox.value.w / 2;
      maxWX = guideFrameBox.value.w / 2;
      minWY = -guideFrameBox.value.h / 2;
      maxWY = guideFrameBox.value.h / 2;
    } else {
      for (const n of nodes.value) {
        const { wx, wy } = nodeToWorldPx(n);
        const half = n.labelText ? 110 * n.scale : (GARMENT_BASE_PX * n.scale) / 2 + 24;
        minWX = Math.min(minWX, wx - half);
        maxWX = Math.max(maxWX, wx + half);
        minWY = Math.min(minWY, wy - half);
        maxWY = Math.max(maxWY, wy + half);
      }
      const pad = 72;
      minWX -= pad;
      maxWX += pad;
      minWY -= pad;
      maxWY += pad;
    }

    const scale2x = 1.6;
    const stageW = Math.max(960, Math.round((maxWX - minWX) * scale2x));
    const stageH = Math.max(760, Math.round((maxWY - minWY) * scale2x));
    const footerH = transparentExport.value ? 0 : 140;
    const outW = stageW;
    const outH = stageH + footerH;

    const canvas = document.createElement('canvas');
    canvas.width = outW;
    canvas.height = outH;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('Canvas 不可用');

    const bd = activeBackdrop.value;
    if (!transparentExport.value) {
      ctx.fillStyle = bd.bg;
      ctx.fillRect(0, 0, outW, outH);
      if (bd.dot) {
        ctx.fillStyle = 'rgba(0,0,0,0.08)';
        for (let gx = 32; gx < outW - 20; gx += 28) {
          for (let gy = 32; gy < stageH - 16; gy += 28) {
            ctx.beginPath();
            ctx.arc(gx, gy, 1.2, 0, Math.PI * 2);
            ctx.fill();
          }
        }
      }
    }

    const ordered = [...nodes.value].sort((a, b) => a.zIndex - b.zIndex);
    for (const node of ordered) {
      const { wx, wy } = nodeToWorldPx(node);
      const cx = (wx - minWX) * scale2x;
      const cy = (wy - minWY) * scale2x;

      if (node.labelText) {
        ctx.save();
        ctx.translate(cx, cy);
        ctx.rotate((node.rotation * Math.PI) / 180);
        ctx.scale(node.flipX ? -node.scale : node.scale, node.scale);
        ctx.font = '600 28px Inter, "Noto Sans SC", sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillStyle = bd.dark && !transparentExport.value ? '#FFFFFF' : '#000000';
        ctx.fillText(node.labelText, 0, 0);
        ctx.restore();
        continue;
      }

      const domEl = viewportRef.value.querySelector(`[data-studio-node="${CSS.escape(node.nodeKey)}"]`);
      if (!domEl) continue;

      let imgEl: HTMLImageElement | null = null;
      const rawImg = domEl.querySelector('img') as HTMLImageElement | null;
      const rawSvg = domEl.querySelector('svg') as SVGSVGElement | null;
      if (rawImg && rawImg.src) {
        imgEl = await loadImageSafe(rawImg.src);
      } else if (rawSvg) {
        const cloned = rawSvg.cloneNode(true) as SVGSVGElement;
        cloned.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
        if (!cloned.getAttribute('width')) cloned.setAttribute('width', '320');
        if (!cloned.getAttribute('height')) cloned.setAttribute('height', '320');
        const svgStr = new XMLSerializer().serializeToString(cloned);
        imgEl = await loadImageSafe('data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svgStr));
      }

      if (imgEl) {
        const baseBox = GARMENT_BASE_PX * scale2x * node.scale;
        ctx.save();
        ctx.translate(cx, cy);
        ctx.rotate((node.rotation * Math.PI) / 180);
        if (node.flipX) ctx.scale(-1, 1);
        const ratio = imgEl.naturalWidth && imgEl.naturalHeight ? imgEl.naturalWidth / imgEl.naturalHeight : 1;
        const drawW = ratio >= 1 ? baseBox : baseBox * ratio;
        const drawH = ratio >= 1 ? baseBox / ratio : baseBox;
        ctx.shadowColor = bd.dark && !transparentExport.value ? 'rgba(0,0,0,0.45)' : 'rgba(0,0,0,0.09)';
        ctx.shadowBlur = 18;
        ctx.shadowOffsetY = 8;
        ctx.drawImage(imgEl, -drawW / 2, -drawH / 2, drawW, drawH);
        ctx.restore();
      }
    }

    if (!transparentExport.value) {
      ctx.strokeStyle = bd.dark ? 'rgba(255,255,255,0.14)' : 'rgba(0,0,0,0.08)';
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.moveTo(48, stageH + 14);
      ctx.lineTo(outW - 48, stageH + 14);
      ctx.stroke();

      ctx.fillStyle = bd.dark ? '#FFFFFF' : '#000000';
      ctx.font = '600 28px Inter, "Noto Sans SC", sans-serif';
      ctx.fillText(props.plan.themeTitle || 'ALTA · 智搭衣橱 OOTD', 48, stageH + 62);

      ctx.fillStyle = bd.dark ? 'rgba(255,255,255,0.65)' : '#666666';
      ctx.font = '400 18px Inter, "Noto Sans SC", sans-serif';
      const summary = nodes.value
        .filter((n) => !n.labelText)
        .map((n) => resolveNodeVisual(n).name)
        .slice(0, 6)
        .join(' · ');
      ctx.fillText(summary, 48, stageH + 98);

      ctx.fillStyle = bd.dark ? '#FFFFFF' : '#000000';
      ctx.font = '500 16px "JetBrains Mono", monospace';
      const today = new Date().toISOString().slice(0, 10);
      ctx.fillText(`ALTA INFINITE STUDIO · ${today} · MATCH ${props.plan.matchScore}%`, outW - 500, stageH + 62);
    }

    const a = document.createElement('a');
    a.href = canvas.toDataURL('image/png');
    a.download = `alta-studio-${props.plan.id}${transparentExport.value ? '-transparent' : ''}.png`;
    a.click();
    toast.show(`已导出 ${outW}×${outH} 高清拼贴图`);
  } catch (e: any) {
    toast.show(e.message || '导出失败');
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

// Compute background dot pattern scaled & translated with camera so panning feels tactile & endless!
const infiniteGridStyle = computed(() => {
  const bd = activeBackdrop.value;
  const step = Math.max(12, Math.round(26 * zoom.value));
  return {
    backgroundColor: bd.bg,
    backgroundImage: bd.dot
      ? `radial-gradient(${bd.dark ? 'rgba(255,255,255,0.16)' : 'rgba(0,0,0,0.12)'} 1.2px, transparent 0)`
      : 'none',
    backgroundSize: `${step}px ${step}px`,
    backgroundPosition: `calc(50% + ${panX.value}px) calc(50% + ${panY.value}px)`,
  };
});
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="fixed inset-0 z-[90] overflow-hidden select-none"
      role="dialog"
      aria-modal="true"
      aria-label="ALTA 无限画布穿搭工作台"
    >
      <!-- 1. FULL-BLEED 100vw x 100vh INFINITE SPATIAL CANVAS (Zero box walls!) -->
      <div
        ref="viewportRef"
        @pointerdown="onViewportPointerDown"
        @pointermove="onGlobalPointerMove"
        @pointerup="onGlobalPointerUp"
        @pointercancel="onGlobalPointerUp"
        @wheel="onViewportWheel"
        class="absolute inset-0 touch-none transition-colors duration-200"
        :class="interaction?.mode === 'pan' ? 'cursor-grabbing' : 'cursor-grab'"
        :style="infiniteGridStyle"
      >
        <!-- World Origin Layer: ScreenPos = 50% + pan + worldPx * zoom -->
        <div
          class="absolute left-1/2 top-1/2 pointer-events-none"
          :style="{
            transform: `translate(${panX}px, ${panY}px) scale(${zoom})`,
            transformOrigin: '0 0',
          }"
        >
          <!-- Optional Non-Clipping Viewfinder Guide Frame (16:9 / 4:5 / 1:1) -->
          <div
            v-if="guideFrameBox"
            class="absolute rounded-3xl border-2 border-dashed pointer-events-none"
            :class="activeBackdrop.dark ? 'border-white/25' : 'border-black/20'"
            :style="{
              width: `${guideFrameBox.w}px`,
              height: `${guideFrameBox.h}px`,
              left: `${-guideFrameBox.w / 2}px`,
              top: `${-guideFrameBox.h / 2}px`,
            }"
          >
            <span
              class="absolute -top-6 left-2 font-mono text-[11px] tracking-wider uppercase"
              :class="activeBackdrop.dark ? 'text-white/45' : 'text-black/40'"
            >
              {{ guideFrameBox.label }}（虚线外仍可自由摆放备选单品）
            </span>
          </div>

          <!-- Infinite Magnetic Snap Crosshair Guides -->
          <div
            v-if="snapWorldX !== null"
            class="absolute -top-[4000px] -bottom-[4000px] h-[8000px] border-l border-dashed pointer-events-none z-[60]"
            :class="activeBackdrop.dark ? 'border-white/45' : 'border-black/35'"
            :style="{ left: `${snapWorldX}px` }"
          />
          <div
            v-if="snapWorldY !== null"
            class="absolute -left-[4000px] -right-[4000px] w-[8000px] border-t border-dashed pointer-events-none z-[60]"
            :class="activeBackdrop.dark ? 'border-white/45' : 'border-black/35'"
            :style="{ top: `${snapWorldY}px` }"
          />

          <!-- Unbounded Garment & Stamp Nodes in World Space -->
          <div
            v-for="node in nodes"
            :key="node.nodeKey"
            :data-studio-node="node.nodeKey"
            @pointerdown.stop="startMoveNode($event, node)"
            @click.stop="selectedKey = node.nodeKey"
            class="absolute pointer-events-auto cursor-grab active:cursor-grabbing"
            :style="{
              left: `${nodeToWorldPx(node).wx}px`,
              top: `${nodeToWorldPx(node).wy}px`,
              zIndex: node.zIndex,
              transform: `translate(-50%, -50%) scale(${node.scale}) rotate(${node.rotation}deg)`,
            }"
          >
            <!-- Typography Sticker Node -->
            <div
              v-if="node.labelText"
              class="relative px-5 py-2.5 rounded-xl font-mono text-[20px] font-semibold tracking-[0.18em] whitespace-nowrap"
              :class="[
                activeBackdrop.dark ? 'text-white' : 'text-black',
                selectedKey === node.nodeKey ? 'ring-2 ring-black bg-white/60 backdrop-blur-[2px]' : '',
              ]"
              :style="{ transform: node.flipX ? 'scaleX(-1)' : 'none' }"
            >
              {{ node.labelText }}
            </div>

            <!-- Garment / Accessory Cutout Node -->
            <div
              v-else
              class="relative w-[240px] h-[240px] p-3 rounded-2xl flex items-center justify-center transition"
              :class="
                selectedKey === node.nodeKey
                  ? activeBackdrop.dark
                    ? 'ring-2 ring-white bg-white/5'
                    : 'ring-1.5 ring-black bg-black/[0.015]'
                  : 'hover:ring-1 hover:ring-black/20'
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
                  class="w-full h-full drop-shadow-[0_16px_26px_rgba(0,0,0,0.12)]"
                />
              </div>

              <!-- Direct On-Canvas Transform Handles when Selected -->
              <template v-if="selectedKey === node.nodeKey">
                <button
                  type="button"
                  @pointerdown.stop="startRotateHandle($event, node)"
                  title="按住拖拽自由旋转"
                  class="absolute -top-9 left-1/2 -translate-x-1/2 w-7 h-7 rounded-full bg-black text-white shadow-md flex items-center justify-center cursor-ew-resize"
                >
                  <AppIcon name="rotate-cw" :size="12" />
                </button>

                <button
                  type="button"
                  @pointerdown.stop
                  @click.stop="removeNode(node.nodeKey)"
                  title="移除单品 (Delete)"
                  class="absolute -top-3 -right-3 w-6 h-6 rounded-full bg-black text-white hover:bg-neutral-700 shadow flex items-center justify-center"
                >
                  <AppIcon name="x" :size="12" />
                </button>

                <button
                  type="button"
                  @pointerdown.stop="startScaleHandle($event, node)"
                  title="按住拖拽无级缩放"
                  class="absolute -bottom-3 -right-3 w-7 h-7 rounded-full bg-white border-2 border-black text-black shadow flex items-center justify-center cursor-nwse-resize"
                >
                  <AppIcon name="maximize" :size="11" />
                </button>
              </template>
            </div>
          </div>
        </div>
      </div>

      <!-- 2. FLOATING TOP HUD BAR (Figma / tldraw / Freeform Floating Capsules) -->
      <div class="pointer-events-none fixed top-3.5 left-4 right-4 z-30 flex flex-wrap items-center justify-between gap-2">
        <!-- Top-Left Capsule: Back + Title + Undo/Redo -->
        <div class="pointer-events-auto inline-flex items-center gap-2 px-2 py-1.5 rounded-full bg-white/92 backdrop-blur-xl border border-black/[0.08] shadow-[0_8px_28px_rgba(0,0,0,0.08)]">
          <button
            type="button"
            @click="emit('close')"
            class="inline-flex items-center gap-1 px-3 py-1 rounded-full bg-black text-white hover:bg-[#262626] text-[12px] font-medium transition"
          >
            <AppIcon name="chevron-left" :size="14" />
            返回
          </button>

          <span class="hidden sm:inline text-[13px] font-semibold text-black px-1 max-w-[180px] truncate">
            {{ plan.themeTitle }}
          </span>

          <div class="inline-flex items-center pl-1 border-l border-black/10">
            <button
              type="button"
              :disabled="!canUndo"
              @click="undo"
              title="撤销 (Ctrl+Z)"
              class="w-7 h-7 rounded-full flex items-center justify-center text-black hover:bg-black/5 disabled:opacity-30 transition"
            >
              <AppIcon name="undo" :size="14" />
            </button>
            <button
              type="button"
              :disabled="!canRedo"
              @click="redo"
              title="重做 (Ctrl+Y)"
              class="w-7 h-7 rounded-full flex items-center justify-center text-black hover:bg-black/5 disabled:opacity-30 transition"
            >
              <AppIcon name="redo" :size="14" />
            </button>
          </div>
        </div>

        <!-- Top-Center Capsule: Layouts + Optional Frame Guide + Paper Backdrop Swatches -->
        <div class="pointer-events-auto hidden lg:inline-flex items-center gap-2 px-3 py-1.5 rounded-full bg-white/92 backdrop-blur-xl border border-black/[0.08] shadow-[0_8px_28px_rgba(0,0,0,0.08)] text-[11.5px]">
          <div class="inline-flex items-center gap-0.5 pr-2 border-r border-black/10">
            <button
              type="button"
              @click="applyLayoutPreset('editorial')"
              class="px-2.5 py-1 rounded-full hover:bg-[#F2F2F2] text-black font-medium transition"
            >
              全身主次
            </button>
            <button
              type="button"
              @click="applyLayoutPreset('wide')"
              class="px-2.5 py-1 rounded-full hover:bg-[#F2F2F2] text-black font-medium transition"
            >
              横向陈列
            </button>
            <button
              type="button"
              @click="applyLayoutPreset('scattered')"
              class="px-2.5 py-1 rounded-full hover:bg-[#F2F2F2] text-black font-medium transition"
            >
              环绕散落
            </button>
          </div>

          <!-- Guide Frame Toggle (default: 无框自由无限画布!) -->
          <div class="inline-flex items-center gap-0.5 pr-2 border-r border-black/10 font-mono text-[11px]">
            <button
              v-for="g in ([
                { id: 'free', label: '无限自由' },
                { id: '16:9', label: '16:9' },
                { id: '4:5', label: '4:5' },
                { id: '1:1', label: '1:1' },
              ] as const)"
              :key="g.id"
              type="button"
              @click="guideRatio = g.id"
              class="px-2 py-1 rounded-full transition"
              :class="guideRatio === g.id ? 'bg-black text-white font-semibold' : 'text-[#666666] hover:text-black'"
            >
              {{ g.label }}
            </button>
          </div>

          <!-- Backdrop Swatches -->
          <div class="inline-flex items-center gap-1.5">
            <button
              v-for="bd in BACKDROPS"
              :key="bd.id"
              type="button"
              @click="backdropId = bd.id"
              :title="bd.label"
              class="w-5 h-5 rounded-full border transition"
              :class="backdropId === bd.id ? 'ring-2 ring-black ring-offset-1 border-black/30' : 'border-black/20'"
              :style="{ backgroundColor: bd.bg }"
            />
          </div>
        </div>

        <!-- Top-Right Capsule: Layer Toggle + Save + Export -->
        <div class="pointer-events-auto inline-flex items-center gap-1.5 px-2 py-1.5 rounded-full bg-white/92 backdrop-blur-xl border border-black/[0.08] shadow-[0_8px_28px_rgba(0,0,0,0.08)]">
          <button
            type="button"
            @click="layersOpen = !layersOpen"
            class="inline-flex items-center gap-1 px-3 py-1 rounded-full text-[12px] font-medium transition"
            :class="layersOpen ? 'bg-black text-white' : 'hover:bg-[#F2F2F2] text-black'"
          >
            <AppIcon name="layers" :size="13" />
            图层 ({{ nodes.length }})
          </button>

          <button
            type="button"
            @click="transparentExport = !transparentExport"
            title="切换导出 PNG 是否使用透明背景"
            class="hidden sm:inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-[11.5px] border transition"
            :class="
              transparentExport
                ? 'border-black bg-[#F2F2F2] text-black font-medium'
                : 'border-transparent text-[#666666] hover:text-black'
            "
          >
            {{ transparentExport ? '透明底✓' : '带底纹' }}
          </button>

          <button
            type="button"
            @click="handleSave"
            :disabled="saving"
            class="px-3 py-1 rounded-full text-[12px] font-medium transition flex items-center gap-1"
            :class="
              hasUnsavedChanges
                ? 'bg-black text-white hover:bg-[#222222]'
                : 'bg-[#F5F5F5] text-black hover:bg-[#EAEAEA]'
            "
          >
            <AppIcon name="check" :size="13" />
            {{ saving ? '保存中...' : hasUnsavedChanges ? '保存排版' : '已保存' }}
          </button>

          <button
            type="button"
            @click="exportStudioPng"
            :disabled="exporting"
            class="px-3.5 py-1 rounded-full bg-black hover:bg-[#262626] text-white text-[12px] font-medium transition flex items-center gap-1"
          >
            <AppIcon name="download" :size="13" />
            {{ exporting ? '渲染中...' : '导出 PNG' }}
          </button>
        </div>
      </div>

      <!-- 3. FLOATING LEFT CLOSET & ACCESSORY ISLAND (Collapsible Glass Card over Infinite Canvas) -->
      <aside
        v-if="leftOpen"
        class="fixed top-16 left-4 bottom-16 w-64 sm:w-72 rounded-3xl bg-white/94 backdrop-blur-xl border border-black/[0.08] shadow-[0_18px_48px_rgba(0,0,0,0.10)] flex flex-col z-30"
      >
        <!-- Middle-Right Collapse Handle -->
        <button
          type="button"
          @click="leftOpen = false"
          title="收起衣橱面板，释放全屏画布"
          class="absolute top-1/2 -right-3 -translate-y-1/2 z-40 w-6 h-12 rounded-full bg-white hover:bg-black hover:text-white border border-black/15 shadow-sm flex items-center justify-center text-[#555555] transition"
        >
          <AppIcon name="chevron-left" :size="13" />
        </button>

        <div class="p-3 border-b border-black/[0.06] space-y-2.5">
          <div class="flex items-center gap-1.5">
            <div class="flex-1 grid grid-cols-3 p-0.5 rounded-full bg-[#F2F2F2] text-[11.5px] font-medium">
              <button
                type="button"
                @click="drawerTab = 'wardrobe'"
                class="py-1.5 rounded-full transition"
                :class="drawerTab === 'wardrobe' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
              >
                我的衣橱
              </button>
              <button
                type="button"
                @click="drawerTab = 'accessories'"
                class="py-1.5 rounded-full transition"
                :class="drawerTab === 'accessories' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
              >
                百搭配饰
              </button>
              <button
                type="button"
                @click="drawerTab = 'text'"
                class="py-1.5 rounded-full transition"
                :class="drawerTab === 'text' ? 'bg-black text-white' : 'text-[#666666] hover:text-black'"
              >
                杂志字贴
              </button>
            </div>
            <button
              type="button"
              @click="leftOpen = false"
              title="收起衣橱面板"
              class="w-7 h-7 rounded-full hover:bg-[#F2F2F2] flex items-center justify-center text-[#555555] hover:text-black transition shrink-0"
            >
              <AppIcon name="panel-left-close" :size="15" />
            </button>
          </div>

          <template v-if="drawerTab === 'wardrobe'">
            <div class="relative">
              <AppIcon name="search" :size="13" class="absolute left-3 top-1/2 -translate-y-1/2 text-[#888888]" />
              <input
                v-model="searchKeyword"
                type="text"
                placeholder="搜索单品名称 / 颜色..."
                class="w-full pl-8 pr-3 py-1.5 rounded-full bg-[#F5F5F5] text-[12px] outline-none focus:ring-1 focus:ring-black"
              />
            </div>
            <div class="flex items-center gap-1 overflow-x-auto no-scrollbar pb-0.5">
              <button
                v-for="cat in CATEGORY_FILTERS"
                :key="cat.key"
                type="button"
                @click="activeCategory = cat.key"
                class="px-2.5 py-0.5 rounded-full text-[11px] whitespace-nowrap transition"
                :class="
                  activeCategory === cat.key
                    ? 'bg-black text-white font-medium'
                    : 'bg-[#F5F5F5] text-[#666666] hover:text-black'
                "
              >
                {{ cat.label }}
              </button>
            </div>
          </template>
        </div>

        <div class="flex-1 overflow-y-auto p-3">
          <div v-if="drawerTab === 'wardrobe'" class="grid grid-cols-2 gap-2.5">
            <button
              v-for="item in filteredWardrobe"
              :key="item.id"
              type="button"
              @click="addWardrobePiece(item)"
              class="group relative rounded-2xl border border-black/[0.07] bg-[#FAFAFA] hover:bg-white hover:border-black p-2 text-left transition flex flex-col"
            >
              <span
                v-if="placedItemIds.has(item.id)"
                class="absolute top-1.5 right-1.5 px-1.5 py-0.5 rounded-full bg-black text-white text-[9px] font-mono z-10"
              >
                已上板
              </span>
              <div class="aspect-square w-full flex items-center justify-center p-1.5">
                <GarmentVisual
                  :image-url="item.processedImageUrl"
                  :svg-type="item.svgType"
                  :color-hex="item.colorHex"
                  :alt="item.name"
                  class="w-full h-full object-contain group-hover:scale-105 transition"
                />
              </div>
              <p class="text-[11px] font-medium text-black truncate mt-1">{{ item.name }}</p>
              <p class="text-[10px] text-[#777777]">{{ categoryLabel(item.category) }} · {{ item.color }}</p>
            </button>
          </div>

          <div v-else-if="drawerTab === 'accessories'" class="grid grid-cols-2 gap-2.5">
            <button
              v-for="acc in STUDIO_ACCESSORIES"
              :key="acc.id"
              type="button"
              @click="addStudioAccessory(acc)"
              class="group rounded-2xl border border-black/[0.07] bg-[#FAFAFA] hover:bg-white hover:border-black p-2 text-left transition flex flex-col"
            >
              <div class="aspect-square w-full flex items-center justify-center p-2">
                <img :src="acc.imageUrl" :alt="acc.name" class="w-full h-full object-contain group-hover:scale-105 transition" />
              </div>
              <p class="text-[11px] font-medium text-black truncate mt-1">{{ acc.name }}</p>
            </button>
          </div>

          <div v-else class="space-y-3">
            <div class="flex gap-1.5">
              <input
                v-model="customStampInput"
                @keyup.enter="addTextStamp(customStampInput)"
                type="text"
                maxlength="36"
                placeholder="输入刊头文字..."
                class="flex-1 px-3 py-1.5 rounded-xl bg-[#F5F5F5] text-[12px] outline-none focus:ring-1 focus:ring-black"
              />
              <button
                type="button"
                @click="addTextStamp(customStampInput)"
                class="px-3 py-1.5 rounded-xl bg-black text-white text-[11.5px] font-medium shrink-0"
              >
                添加
              </button>
            </div>
            <div class="space-y-1.5">
              <button
                v-for="preset in TEXT_PRESETS"
                :key="preset"
                type="button"
                @click="addTextStamp(preset)"
                class="w-full py-2 px-3 rounded-xl border border-black/10 bg-[#FAFAFA] hover:bg-black hover:text-white font-mono text-[11px] tracking-[0.14em] text-left transition"
              >
                {{ preset }}
              </button>
            </div>
          </div>
        </div>
      </aside>

      <!-- Collapsed Left Reopen Pill -->
      <button
        v-else
        type="button"
        @click="leftOpen = true"
        title="展开衣橱与配饰抽屉"
        class="fixed top-20 left-4 z-30 inline-flex items-center gap-1.5 px-3.5 py-2 rounded-full bg-white/94 hover:bg-black hover:text-white border border-black/15 shadow-[0_8px_24px_rgba(0,0,0,0.10)] text-[12px] font-medium text-black transition"
      >
        <AppIcon name="panel-left-open" :size="15" />
        打开衣橱抽屉
      </button>

      <!-- 4. FLOATING RIGHT LAYER DRAWER (On-Demand Only — Keeps Full Right Side Open by Default!) -->
      <aside
        v-if="layersOpen"
        class="fixed top-16 right-4 bottom-16 w-64 rounded-3xl bg-white/94 backdrop-blur-xl border border-black/[0.08] shadow-[0_18px_48px_rgba(0,0,0,0.10)] flex flex-col z-30 overflow-hidden"
      >
        <div class="p-3.5 border-b border-black/[0.06] flex items-center justify-between">
          <span class="text-[12px] font-semibold text-black">叠穿图层栈 (顶 → 底)</span>
          <button
            type="button"
            @click="layersOpen = false"
            title="收起图层浮窗"
            class="w-7 h-7 rounded-full hover:bg-[#F2F2F2] flex items-center justify-center text-[#555555] hover:text-black transition"
          >
            <AppIcon name="panel-right-close" :size="15" />
          </button>
        </div>

        <div class="flex-1 overflow-y-auto p-3 space-y-1.5">
          <div
            v-for="layer in orderedLayersDesc"
            :key="layer.nodeKey"
            @click="selectedKey = layer.nodeKey"
            class="px-2.5 py-2 rounded-xl border flex items-center justify-between gap-2 cursor-pointer transition"
            :class="
              selectedKey === layer.nodeKey
                ? 'border-black bg-[#F7F7F7]'
                : 'border-black/[0.06] bg-white hover:border-black/25'
            "
          >
            <div class="flex items-center gap-2 min-w-0">
              <div class="w-8 h-8 rounded-lg bg-[#F5F5F5] p-1 flex items-center justify-center shrink-0">
                <span v-if="layer.labelText" class="font-mono text-[10px] font-bold">Aa</span>
                <GarmentVisual
                  v-else
                  :image-url="resolveNodeVisual(layer).imageUrl"
                  :svg-type="resolveNodeVisual(layer).svgType"
                  :color-hex="resolveNodeVisual(layer).colorHex"
                  class="w-full h-full object-contain"
                />
              </div>
              <p class="text-[11.5px] font-medium text-black truncate">{{ resolveNodeVisual(layer).name }}</p>
            </div>

            <div class="flex items-center gap-0.5 shrink-0" @click.stop>
              <button
                type="button"
                @click="moveLayer(layer, 1)"
                title="上移一层"
                class="w-6 h-6 rounded-lg hover:bg-black/10 flex items-center justify-center text-[#555555]"
              >
                <AppIcon name="arrow-up" :size="11" />
              </button>
              <button
                type="button"
                @click="moveLayer(layer, -1)"
                title="下移一层"
                class="w-6 h-6 rounded-lg hover:bg-black/10 flex items-center justify-center text-[#555555]"
              >
                <AppIcon name="arrow-down" :size="11" />
              </button>
            </div>
          </div>
        </div>
      </aside>

      <!-- 5. CONTEXTUAL FLOATING ACTION PILL WHEN AN ITEM IS SELECTED (Figma / Freeform Pattern) -->
      <div
        v-if="selectedNode && selectedVisual"
        class="fixed bottom-4 left-1/2 -translate-x-1/2 z-40 px-3.5 py-2 rounded-full bg-black text-white shadow-[0_14px_36px_rgba(0,0,0,0.22)] flex flex-wrap items-center gap-2 text-[11.5px]"
      >
        <span class="font-medium max-w-[130px] truncate px-1">{{ selectedVisual.name }}</span>

        <div class="flex items-center gap-1 pl-2 border-l border-white/15 font-mono">
          <button
            type="button"
            @click="selectedNode.scale = Number(Math.max(0.3, selectedNode.scale - 0.1).toFixed(2)); pushHistory(nodes)"
            class="w-6 h-6 rounded-full bg-white/15 hover:bg-white/25 flex items-center justify-center"
          >
            −
          </button>
          <span class="w-10 text-center">{{ Math.round(selectedNode.scale * 100) }}%</span>
          <button
            type="button"
            @click="selectedNode.scale = Number(Math.min(3.0, selectedNode.scale + 0.1).toFixed(2)); pushHistory(nodes)"
            class="w-6 h-6 rounded-full bg-white/15 hover:bg-white/25 flex items-center justify-center"
          >
            +
          </button>
        </div>

        <div class="flex items-center gap-1 pl-2 border-l border-white/15">
          <button
            type="button"
            @click="toggleFlipX"
            class="px-2.5 h-6 rounded-full hover:bg-white/25 flex items-center gap-1 transition"
            :class="selectedNode.flipX ? 'bg-white text-black font-semibold' : 'bg-white/15'"
          >
            <AppIcon name="flip" :size="12" />
            镜像
          </button>
          <button
            type="button"
            @click="duplicateSelected"
            class="px-2.5 h-6 rounded-full bg-white/15 hover:bg-white/25 flex items-center gap-1 transition"
          >
            <AppIcon name="copy" :size="12" />
            复制
          </button>
          <button
            type="button"
            @click="bringToFront(selectedNode)"
            class="px-2.5 h-6 rounded-full bg-white/15 hover:bg-white/25 transition"
          >
            置顶
          </button>
          <button
            type="button"
            @click="sendToBack(selectedNode)"
            class="px-2.5 h-6 rounded-full bg-white/15 hover:bg-white/25 transition"
          >
            置底
          </button>
          <button
            v-if="selectedVisual.slot && !selectedVisual.slot.anchor"
            type="button"
            @click="emit('swap-slot', selectedVisual.slot.slotKey, selectedVisual.slot.item.id)"
            class="px-2.5 h-6 rounded-full bg-white text-black font-medium flex items-center gap-1"
          >
            <AppIcon name="refresh" :size="11" />
            换同款
          </button>
          <button
            type="button"
            @click="removeNode(selectedNode.nodeKey)"
            title="删除 (Delete)"
            class="w-6 h-6 rounded-full bg-white/15 hover:bg-red-500 flex items-center justify-center transition"
          >
            <AppIcon name="trash" :size="12" />
          </button>
        </div>
      </div>

      <!-- Subtle Guide Pill when Nothing is Selected -->
      <div
        v-else
        class="pointer-events-none hidden md:flex fixed bottom-4 left-1/2 -translate-x-1/2 z-30 px-4 py-1.5 rounded-full bg-white/85 backdrop-blur border border-black/[0.08] text-[11px] text-[#666666] gap-2.5 shadow-sm"
      >
        <span>按住空白处拖拽可无限平移画布</span>
        <span>·</span>
        <span>鼠标滚轮以光标为中心缩放</span>
        <span>·</span>
        <span>自由拖拽衣物无边界限制</span>
      </div>

      <!-- 6. BOTTOM-RIGHT CAMERA ZOOM & FIT-TO-SCREEN HUD (Figma / Miro / tldraw Standard) -->
      <div class="fixed bottom-4 right-4 z-30 inline-flex items-center gap-1 px-2 py-1.5 rounded-full bg-white/94 backdrop-blur-xl border border-black/[0.08] shadow-[0_8px_24px_rgba(0,0,0,0.10)] font-mono text-[11.5px]">
        <button
          type="button"
          @click="stepZoom(-0.15)"
          title="缩小画布"
          class="w-6 h-6 rounded-full hover:bg-[#F2F2F2] flex items-center justify-center text-black"
        >
          −
        </button>
        <button
          type="button"
          @click="zoom = 1; panX = leftOpen ? 120 : 0; panY = 0"
          title="重置 100% 比例"
          class="px-1.5 text-black hover:underline"
        >
          {{ Math.round(zoom * 100) }}%
        </button>
        <button
          type="button"
          @click="stepZoom(0.15)"
          title="放大画布"
          class="w-6 h-6 rounded-full hover:bg-[#F2F2F2] flex items-center justify-center text-black"
        >
          +
        </button>
        <button
          type="button"
          @click="fitAllToViewport"
          title="自动居中并放大铺满全部单品 (Shift+1)"
          class="ml-1 px-2.5 py-0.5 rounded-full bg-black text-white text-[11px] font-sans font-medium hover:bg-[#262626] transition"
        >
          适配全屏
        </button>
      </div>
    </div>
  </Teleport>
</template>
