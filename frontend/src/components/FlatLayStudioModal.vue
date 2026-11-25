<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
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

// Studio UI state
const drawerTab = ref<'wardrobe' | 'accessories' | 'text'>('wardrobe');
const searchKeyword = ref('');
const activeCategory = ref<string>('all');
const leftOpen = ref(true);
const rightOpen = ref(true);
const aspectRatio = ref<'4:5' | '1:1' | '9:16'>('4:5');
const backdropId = ref<'dot' | 'white' | 'oat' | 'stone' | 'noir'>('dot');
const boardZoom = ref(1);
const customStampInput = ref('');
const exporting = ref(false);
const hasUnsavedChanges = ref(false);

const artboardRef = ref<HTMLElement | null>(null);
const nodes = ref<CollageNode[]>([]);
const selectedKey = ref<string | null>(null);

// Snap guides state while dragging
const snapGuideX = ref<number | null>(null);
const snapGuideY = ref<number | null>(null);

// Undo / Redo history stack
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
  { id: 'dot' as const, label: '建筑点阵', bg: '#FFFFFF', dot: true, dark: false },
  { id: 'white' as const, label: '纯白画廊', bg: '#FFFFFF', dot: false, dark: false },
  { id: 'oat' as const, label: '暖燕麦纸', bg: '#F6F3EE', dot: false, dark: false },
  { id: 'stone' as const, label: '买手微灰', bg: '#EFEFEF', dot: false, dark: false },
  { id: 'noir' as const, label: '暗房黑底', bg: '#161616', dot: false, dark: true },
];

const activeBackdrop = computed(() => BACKDROPS.find((b) => b.id === backdropId.value) || BACKDROPS[0]);

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

watch(
  () => [props.open, props.plan.id],
  ([isOpen]) => {
    if (isOpen) {
      nodes.value = cloneNodes(props.initialNodes);
      historyStack.value = [cloneNodes(props.initialNodes)];
      historyIndex.value = 0;
      hasUnsavedChanges.value = false;
      selectedKey.value = nodes.value[0]?.nodeKey || null;
      document.body.style.overflow = 'hidden';
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
});

onBeforeUnmount(() => {
  document.body.style.overflow = '';
  window.removeEventListener('keydown', onKeyDown);
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
  for (const it of wardrobeStore.items) {
    map[it.id] = it;
  }
  for (const s of props.plan.slots || []) {
    map[s.item.id] = s.item;
  }
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

const artboardDimensions = computed(() => {
  if (aspectRatio.value === '1:1') return { width: 640, height: 640, outW: 1200, outH: 1200 };
  if (aspectRatio.value === '9:16') return { width: 460, height: 818, outW: 1080, outH: 1920 };
  return { width: 580, height: 725, outW: 1080, outH: 1350 };
});

// Pointer interaction modes: move | scale | rotate
interface InteractionState {
  mode: 'move' | 'scale' | 'rotate';
  nodeKey: string;
  startClientX: number;
  startClientY: number;
  origX: number;
  origY: number;
  origScale: number;
  origRotation: number;
  centerClientX: number;
  centerClientY: number;
  startDist: number;
  startAngleDeg: number;
  boardW: number;
  boardH: number;
  moved: boolean;
}
const interaction = ref<InteractionState | null>(null);

function startMove(e: PointerEvent, node: CollageNode) {
  const rect = artboardRef.value?.getBoundingClientRect();
  if (!rect) return;
  selectedKey.value = node.nodeKey;
  (e.currentTarget as HTMLElement)?.setPointerCapture?.(e.pointerId);
  interaction.value = {
    mode: 'move',
    nodeKey: node.nodeKey,
    startClientX: e.clientX,
    startClientY: e.clientY,
    origX: node.x,
    origY: node.y,
    origScale: node.scale,
    origRotation: node.rotation,
    centerClientX: 0,
    centerClientY: 0,
    startDist: 1,
    startAngleDeg: 0,
    boardW: rect.width,
    boardH: rect.height,
    moved: false,
  };
}

function startScaleHandle(e: PointerEvent, node: CollageNode) {
  const rect = artboardRef.value?.getBoundingClientRect();
  if (!rect) return;
  selectedKey.value = node.nodeKey;
  (e.currentTarget as HTMLElement)?.setPointerCapture?.(e.pointerId);
  const cx = rect.left + (node.x / 100) * rect.width;
  const cy = rect.top + (node.y / 100) * rect.height;
  const dist = Math.max(20, Math.hypot(e.clientX - cx, e.clientY - cy));
  interaction.value = {
    mode: 'scale',
    nodeKey: node.nodeKey,
    startClientX: e.clientX,
    startClientY: e.clientY,
    origX: node.x,
    origY: node.y,
    origScale: node.scale,
    origRotation: node.rotation,
    centerClientX: cx,
    centerClientY: cy,
    startDist: dist,
    startAngleDeg: 0,
    boardW: rect.width,
    boardH: rect.height,
    moved: false,
  };
}

function startRotateHandle(e: PointerEvent, node: CollageNode) {
  const rect = artboardRef.value?.getBoundingClientRect();
  if (!rect) return;
  selectedKey.value = node.nodeKey;
  (e.currentTarget as HTMLElement)?.setPointerCapture?.(e.pointerId);
  const cx = rect.left + (node.x / 100) * rect.width;
  const cy = rect.top + (node.y / 100) * rect.height;
  const angle = (Math.atan2(e.clientY - cy, e.clientX - cx) * 180) / Math.PI;
  interaction.value = {
    mode: 'rotate',
    nodeKey: node.nodeKey,
    startClientX: e.clientX,
    startClientY: e.clientY,
    origX: node.x,
    origY: node.y,
    origScale: node.scale,
    origRotation: node.rotation,
    centerClientX: cx,
    centerClientY: cy,
    startDist: 1,
    startAngleDeg: angle,
    boardW: rect.width,
    boardH: rect.height,
    moved: false,
  };
}

function onBoardPointerMove(e: PointerEvent) {
  const st = interaction.value;
  if (!st) return;
  const node = nodes.value.find((n) => n.nodeKey === st.nodeKey);
  if (!node) return;

  if (st.mode === 'move') {
    const dxPct = ((e.clientX - st.startClientX) / st.boardW) * 100;
    const dyPct = ((e.clientY - st.startClientY) / st.boardH) * 100;
    let nextX = Math.max(6, Math.min(94, st.origX + dxPct));
    let nextY = Math.max(6, Math.min(94, st.origY + dyPct));

    // Magnetic snap to center (50%) and peer items unless Shift is held
    snapGuideX.value = null;
    snapGuideY.value = null;
    if (!e.shiftKey) {
      const xTargets = [50, ...nodes.value.filter((n) => n.nodeKey !== node.nodeKey).map((n) => n.x)];
      const yTargets = [50, ...nodes.value.filter((n) => n.nodeKey !== node.nodeKey).map((n) => n.y)];
      for (const tx of xTargets) {
        if (Math.abs(nextX - tx) < 1.3) {
          nextX = tx;
          snapGuideX.value = tx;
          break;
        }
      }
      for (const ty of yTargets) {
        if (Math.abs(nextY - ty) < 1.3) {
          nextY = ty;
          snapGuideY.value = ty;
          break;
        }
      }
    }

    node.x = Number(nextX.toFixed(1));
    node.y = Number(nextY.toFixed(1));
    st.moved = true;
  } else if (st.mode === 'scale') {
    const dist = Math.max(16, Math.hypot(e.clientX - st.centerClientX, e.clientY - st.centerClientY));
    const ratio = dist / st.startDist;
    node.scale = Number(Math.max(0.35, Math.min(2.2, st.origScale * ratio)).toFixed(2));
    st.moved = true;
  } else if (st.mode === 'rotate') {
    const angle = (Math.atan2(e.clientY - st.centerClientY, e.clientX - st.centerClientX) * 180) / Math.PI;
    let deg = Math.round(st.origRotation + (angle - st.startAngleDeg));
    if (deg > 180) deg -= 360;
    if (deg < -180) deg += 360;
    if (!e.shiftKey && Math.abs(deg) < 4) deg = 0;
    node.rotation = deg;
    st.moved = true;
  }
}

function onBoardPointerUp() {
  if (interaction.value?.moved) {
    pushHistory(nodes.value);
  }
  interaction.value = null;
  snapGuideX.value = null;
  snapGuideY.value = null;
}

function onNodeWheel(e: WheelEvent, node: CollageNode) {
  if (selectedKey.value !== node.nodeKey) return;
  e.preventDefault();
  const delta = e.deltaY < 0 ? 0.06 : -0.06;
  node.scale = Number(Math.max(0.35, Math.min(2.2, node.scale + delta)).toFixed(2));
  pushHistory(nodes.value);
}

// Add pieces from Left Drawer
function addWardrobePiece(item: WardrobeItem) {
  const maxZ = Math.max(0, ...nodes.value.map((n) => n.zIndex));
  const jitter = (nodes.value.length % 5) * 4 - 8;
  const newNode: CollageNode = {
    nodeKey: `wardrobe-${item.id}-${Date.now()}`,
    slotKey: item.category || 'extra',
    itemId: item.id,
    x: 50 + jitter,
    y: 50 + jitter,
    scale: item.category === 'accessory' ? 0.82 : 1.02,
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
  toast.show(`已将「${item.name}」放入画板`);
}

function addStudioAccessory(acc: (typeof STUDIO_ACCESSORIES)[number]) {
  const maxZ = Math.max(0, ...nodes.value.map((n) => n.zIndex));
  const jitter = (nodes.value.length % 4) * 5 - 6;
  const newNode: CollageNode = {
    nodeKey: `${acc.id}-${Date.now()}`,
    slotKey: 'accessory',
    itemId: 0,
    x: 72 + jitter,
    y: 42 + jitter,
    scale: 0.78,
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
  const newNode: CollageNode = {
    nodeKey: `text-${Date.now()}`,
    slotKey: 'label',
    itemId: 0,
    x: 50,
    y: 12,
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

// Inspector actions
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
    x: Math.min(90, src.x + 6),
    y: Math.min(90, src.y + 6),
    zIndex: maxZ + 1,
  };
  nodes.value.push(copy);
  selectedKey.value = copy.nodeKey;
  pushHistory(nodes.value);
}

function removeNode(nodeKey: string) {
  if (nodes.value.length <= 1) {
    toast.show('画板上至少保留一件单品');
    return;
  }
  nodes.value = nodes.value.filter((n) => n.nodeKey !== nodeKey);
  if (selectedKey.value === nodeKey) {
    selectedKey.value = nodes.value[nodes.value.length - 1]?.nodeKey || null;
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

// 3 Curated Magazine Auto-Layout Templates
function applyLayoutPreset(preset: 'editorial' | 'grid' | 'diagonal') {
  const garments = nodes.value.filter((n) => !n.labelText);
  if (preset === 'editorial') {
    const catCoords: Record<string, { x: number; y: number; scale: number; rot: number; z: number }> = {
      outerwear: { x: 27, y: 33, scale: 1.22, rot: -4, z: 4 },
      top: { x: 56, y: 24, scale: 1.04, rot: 2, z: 2 },
      dress: { x: 40, y: 46, scale: 1.25, rot: -2, z: 3 },
      bottom: { x: 49, y: 65, scale: 1.18, rot: -1, z: 3 },
      shoes: { x: 77, y: 78, scale: 0.92, rot: 5, z: 5 },
      accessory: { x: 79, y: 37, scale: 0.84, rot: 7, z: 6 },
    };
    const seen: Record<string, number> = {};
    garments.forEach((n, idx) => {
      const c = catCoords[n.slotKey] || { x: 30 + (idx % 3) * 20, y: 30 + Math.floor(idx / 3) * 30, scale: 0.95, rot: 0, z: idx + 1 };
      const count = seen[n.slotKey] || 0;
      seen[n.slotKey] = count + 1;
      n.x = Math.min(88, c.x + count * 8);
      n.y = Math.min(88, c.y + count * 9);
      n.scale = c.scale;
      n.rotation = c.rot;
      n.zIndex = c.z + count;
    });
  } else if (preset === 'grid') {
    const cols = garments.length <= 4 ? 2 : 3;
    const rows = Math.ceil(garments.length / cols);
    garments.forEach((n, idx) => {
      const col = idx % cols;
      const row = Math.floor(idx / cols);
      n.x = Number((((col + 0.5) / cols) * 76 + 12).toFixed(1));
      n.y = Number((((row + 0.5) / rows) * 74 + 13).toFixed(1));
      n.scale = cols === 2 ? 1.08 : 0.92;
      n.rotation = 0;
      n.zIndex = idx + 1;
    });
  } else {
    garments.forEach((n, idx) => {
      const t = garments.length === 1 ? 0.5 : idx / (garments.length - 1);
      n.x = Number((22 + t * 56 + (idx % 2 === 0 ? -4 : 5)).toFixed(1));
      n.y = Number((20 + t * 60).toFixed(1));
      n.scale = idx === 0 ? 1.18 : 0.96;
      n.rotation = idx % 2 === 0 ? -6 : 6;
      n.zIndex = idx + 1;
    });
  }
  normalizeZ();
  pushHistory(nodes.value);
  toast.show('已应用画报构图预设');
}

function handleSave() {
  emit('save-layout', cloneNodes(nodes.value));
  hasUnsavedChanges.value = false;
}

function onKeyDown(e: KeyboardEvent) {
  if (!props.open) return;
  const tag = (e.target as HTMLElement | null)?.tagName?.toLowerCase();
  if (tag === 'input' || tag === 'textarea' || tag === 'select') return;

  if (e.key === 'Escape') {
    if (selectedKey.value) {
      selectedKey.value = null;
    } else {
      emit('close');
    }
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
    const step = e.shiftKey ? 2.5 : 0.7;
    if (e.key === 'ArrowLeft') selectedNode.value.x = Math.max(5, Number((selectedNode.value.x - step).toFixed(1)));
    if (e.key === 'ArrowRight') selectedNode.value.x = Math.min(95, Number((selectedNode.value.x + step).toFixed(1)));
    if (e.key === 'ArrowUp') selectedNode.value.y = Math.max(5, Number((selectedNode.value.y - step).toFixed(1)));
    if (e.key === 'ArrowDown') selectedNode.value.y = Math.min(95, Number((selectedNode.value.y + step).toFixed(1)));
    pushHistory(nodes.value);
  }
}

async function exportStudioPng() {
  if (!artboardRef.value || exporting.value) return;
  exporting.value = true;
  try {
    const { outW, outH } = artboardDimensions.value;
    const footerH = 180;
    const stageH = outH - footerH;
    const canvas = document.createElement('canvas');
    canvas.width = outW;
    canvas.height = outH;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('Canvas 不可用');

    const bd = activeBackdrop.value;
    ctx.fillStyle = bd.dark ? '#111111' : '#FFFFFF';
    ctx.fillRect(0, 0, outW, outH);

    ctx.fillStyle = bd.bg;
    ctx.fillRect(36, 36, outW - 72, stageH - 36);

    if (bd.dot) {
      ctx.fillStyle = 'rgba(0,0,0,0.08)';
      for (let gx = 60; gx < outW - 50; gx += 32) {
        for (let gy = 60; gy < stageH - 20; gy += 32) {
          ctx.beginPath();
          ctx.arc(gx, gy, 1.3, 0, Math.PI * 2);
          ctx.fill();
        }
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
        ctx.scale(node.flipX ? -node.scale : node.scale, node.scale);
        ctx.font = '600 28px Inter, "Noto Sans SC", sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillStyle = bd.dark ? '#FFFFFF' : '#000000';
        ctx.fillText(node.labelText, 0, 0);
        ctx.restore();
        continue;
      }

      const domEl = artboardRef.value.querySelector(`[data-studio-node="${CSS.escape(node.nodeKey)}"]`);
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
        const baseBox = 305 * node.scale;
        ctx.save();
        ctx.translate(cx, cy);
        ctx.rotate((node.rotation * Math.PI) / 180);
        if (node.flipX) ctx.scale(-1, 1);
        const ratio = imgEl.naturalWidth && imgEl.naturalHeight ? imgEl.naturalWidth / imgEl.naturalHeight : 1;
        const drawW = ratio >= 1 ? baseBox : baseBox * ratio;
        const drawH = ratio >= 1 ? baseBox / ratio : baseBox;
        ctx.shadowColor = bd.dark ? 'rgba(0,0,0,0.45)' : 'rgba(0,0,0,0.09)';
        ctx.shadowBlur = 20;
        ctx.shadowOffsetY = 8;
        ctx.drawImage(imgEl, -drawW / 2, -drawH / 2, drawW, drawH);
        ctx.restore();
      }
    }

    // Editorial Footer
    ctx.strokeStyle = bd.dark ? 'rgba(255,255,255,0.14)' : 'rgba(0,0,0,0.08)';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(56, stageH + 22);
    ctx.lineTo(outW - 56, stageH + 22);
    ctx.stroke();

    ctx.fillStyle = bd.dark ? '#FFFFFF' : '#000000';
    ctx.font = '600 32px Inter, "Noto Sans SC", sans-serif';
    ctx.fillText(props.plan.themeTitle || 'ALTA · 智搭衣橱 OOTD', 56, stageH + 78);

    ctx.fillStyle = bd.dark ? 'rgba(255,255,255,0.65)' : '#666666';
    ctx.font = '400 20px Inter, "Noto Sans SC", sans-serif';
    const summary = nodes.value
      .filter((n) => !n.labelText)
      .map((n) => resolveNodeVisual(n).name)
      .slice(0, 5)
      .join(' · ');
    ctx.fillText(summary, 56, stageH + 116);

    ctx.fillStyle = bd.dark ? '#FFFFFF' : '#000000';
    ctx.font = '500 18px "JetBrains Mono", monospace';
    const today = new Date().toISOString().slice(0, 10);
    ctx.fillText(`ALTA STUDIO · ${aspectRatio.value} · ${today} · MATCH ${props.plan.matchScore}%`, 56, stageH + 154);

    const a = document.createElement('a');
    a.href = canvas.toDataURL('image/png');
    a.download = `alta-studio-${props.plan.id}-${aspectRatio.value.replace(':', 'x')}.png`;
    a.click();
    toast.show(`已导出 ${outW}×${outH} 高清杂志海报`);
  } catch (e: any) {
    toast.show(e.message || '导出海报失败');
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
  <Teleport to="body">
    <div
      v-if="open"
      class="fixed inset-0 z-[90] bg-white flex flex-col select-none"
      role="dialog"
      aria-modal="true"
      aria-label="ALTA 全屏自由拼贴工作台"
    >
      <!-- Top Studio Command Bar -->
      <header class="h-14 px-4 border-b border-black/[0.08] bg-white flex items-center justify-between gap-3 shrink-0">
        <!-- Left: Back + Plan Title + Open in Standalone Window -->
        <div class="flex items-center gap-2.5 min-w-0">
          <button
            type="button"
            @click="emit('close')"
            class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full border border-black/15 bg-white hover:bg-black hover:text-white text-[12.5px] font-medium transition shrink-0"
          >
            <AppIcon name="chevron-left" :size="15" />
            返回搭配页
          </button>

          <div class="hidden sm:flex items-center gap-2 min-w-0">
            <span class="px-2 py-0.5 rounded bg-black text-white font-mono text-[10px] tracking-wider uppercase shrink-0">
              STUDIO
            </span>
            <h2 class="text-[14px] font-semibold text-black truncate max-w-[240px]">
              {{ plan.themeTitle }}
            </h2>
            <span class="font-mono text-[11px] text-[#666666] shrink-0">
              {{ nodes.length }} 件元素
            </span>
          </div>
        </div>

        <!-- Center: Undo/Redo + Layout Templates + Aspect Ratio + Zoom -->
        <div class="flex items-center gap-2 overflow-x-auto no-scrollbar">
          <div class="inline-flex items-center rounded-full bg-[#F5F5F5] p-0.5 border border-black/[0.06]">
            <button
              type="button"
              :disabled="!canUndo"
              @click="undo"
              title="撤销 (Ctrl+Z)"
              class="w-7 h-7 rounded-full flex items-center justify-center text-black hover:bg-white disabled:opacity-30 transition"
            >
              <AppIcon name="undo" :size="14" />
            </button>
            <button
              type="button"
              :disabled="!canRedo"
              @click="redo"
              title="重做 (Ctrl+Y)"
              class="w-7 h-7 rounded-full flex items-center justify-center text-black hover:bg-white disabled:opacity-30 transition"
            >
              <AppIcon name="redo" :size="14" />
            </button>
          </div>

          <!-- Auto-Layout Templates -->
          <div class="hidden lg:inline-flex items-center rounded-full bg-[#F5F5F5] p-0.5 border border-black/[0.06] text-[11.5px]">
            <button
              type="button"
              @click="applyLayoutPreset('editorial')"
              class="px-2.5 py-1 rounded-full hover:bg-white text-black font-medium transition"
            >
              全身主次
            </button>
            <button
              type="button"
              @click="applyLayoutPreset('grid')"
              class="px-2.5 py-1 rounded-full hover:bg-white text-black font-medium transition"
            >
              买手陈列
            </button>
            <button
              type="button"
              @click="applyLayoutPreset('diagonal')"
              class="px-2.5 py-1 rounded-full hover:bg-white text-black font-medium transition"
            >
              画报散落
            </button>
          </div>

          <!-- Aspect Ratio -->
          <div class="inline-flex items-center rounded-full bg-[#F5F5F5] p-0.5 border border-black/[0.06] font-mono text-[11px]">
            <button
              v-for="r in (['4:5', '1:1', '9:16'] as const)"
              :key="r"
              type="button"
              @click="aspectRatio = r"
              class="px-2.5 py-1 rounded-full transition"
              :class="aspectRatio === r ? 'bg-black text-white font-semibold' : 'text-[#555555] hover:text-black'"
            >
              {{ r }}
            </button>
          </div>

          <!-- Canvas Zoom -->
          <div class="hidden xl:inline-flex items-center gap-1 rounded-full bg-[#F5F5F5] px-2 py-0.5 border border-black/[0.06] font-mono text-[11px]">
            <button
              type="button"
              @click="boardZoom = Math.max(0.65, Number((boardZoom - 0.1).toFixed(2)))"
              class="w-5 h-5 rounded-full hover:bg-white flex items-center justify-center"
            >
              −
            </button>
            <button type="button" @click="boardZoom = 1" class="px-1 hover:underline">
              {{ Math.round(boardZoom * 100) }}%
            </button>
            <button
              type="button"
              @click="boardZoom = Math.min(1.35, Number((boardZoom + 0.1).toFixed(2)))"
              class="w-5 h-5 rounded-full hover:bg-white flex items-center justify-center"
            >
              +
            </button>
          </div>
        </div>

        <!-- Right: Save + Export -->
        <div class="flex items-center gap-2 shrink-0">
          <button
            type="button"
            @click="handleSave"
            :disabled="saving"
            class="px-3.5 py-1.5 rounded-full text-[12px] font-medium transition flex items-center gap-1"
            :class="
              hasUnsavedChanges
                ? 'bg-black text-white hover:bg-[#222222]'
                : 'bg-white border border-black/15 text-black hover:bg-[#F5F5F5]'
            "
          >
            <AppIcon name="check" :size="13" />
            {{ saving ? '保存中...' : hasUnsavedChanges ? '保存排版' : '已保存' }}
          </button>

          <button
            type="button"
            @click="exportStudioPng"
            :disabled="exporting"
            class="px-3.5 py-1.5 rounded-full bg-black hover:bg-[#262626] text-white text-[12px] font-medium transition flex items-center gap-1"
          >
            <AppIcon name="download" :size="13" />
            {{ exporting ? '渲染中...' : '导出海报' }}
          </button>
        </div>
      </header>

      <!-- Main 3-Column Studio Body -->
      <div class="flex-1 min-h-0 flex overflow-hidden relative">
        <!-- LEFT PANE: Closet & Studio Accessory Drawer -->
        <aside
          v-if="leftOpen"
          class="relative w-64 sm:w-72 border-r border-black/[0.08] bg-white flex flex-col shrink-0 min-h-0 z-20"
        >
          <!-- Edge Collapse Handle (Middle Right of Left Sidebar) -->
          <button
            type="button"
            @click="leftOpen = false"
            title="收起衣橱侧栏"
            class="absolute top-1/2 -right-3 -translate-y-1/2 z-30 w-6 h-12 rounded-full bg-white hover:bg-black hover:text-white border border-black/15 shadow-sm flex items-center justify-center text-[#555555] transition"
          >
            <AppIcon name="chevron-left" :size="13" />
          </button>

          <!-- Drawer Tabs + Collapse Icon -->
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
                title="收起左侧衣橱栏"
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

          <!-- Drawer Scroll Content -->
          <div class="flex-1 overflow-y-auto p-3">
            <!-- Tab 1: User's Full Digital Wardrobe -->
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

            <!-- Tab 2: Curated Alta Studio Accessories -->
            <div v-else-if="drawerTab === 'accessories'" class="space-y-2.5">
              <p class="text-[11px] text-[#666666] leading-relaxed">
                点击任意透明底配饰即可叠加到画板上，补齐耳饰、腕表、墨镜与包袋细节：
              </p>
              <div class="grid grid-cols-2 gap-2.5">
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
            </div>

            <!-- Tab 3: Editorial Magazine Typography Stamps -->
            <div v-else class="space-y-3">
              <div class="space-y-1.5">
                <label class="text-[11px] font-medium text-[#555555]">自定义杂志刊头 / 穿搭心情</label>
                <div class="flex gap-1.5">
                  <input
                    v-model="customStampInput"
                    @keyup.enter="addTextStamp(customStampInput)"
                    type="text"
                    maxlength="36"
                    placeholder="如：WEEKEND COFFEE LOOK"
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
              </div>

              <div class="space-y-1.5 pt-1">
                <p class="text-[11px] text-[#666666]">一键添加极简英文刊头贴纸：</p>
                <button
                  v-for="preset in TEXT_PRESETS"
                  :key="preset"
                  type="button"
                  @click="addTextStamp(preset)"
                  class="w-full py-2.5 px-3 rounded-xl border border-black/10 bg-[#FAFAFA] hover:bg-black hover:text-white font-mono text-[11.5px] tracking-[0.14em] text-left transition"
                >
                  {{ preset }}
                </button>
              </div>
            </div>
          </div>
        </aside>

        <!-- CENTER PANE: Large Interactive Artboard Workspace -->
        <main
          @click.self="selectedKey = null"
          class="flex-1 min-w-0 bg-[#F2F2F2] overflow-auto flex items-center justify-center p-4 sm:p-8 relative"
        >
          <!-- Floating Left Reopen Trigger when Left Sidebar is Collapsed -->
          <button
            v-if="!leftOpen"
            type="button"
            @click="leftOpen = true"
            title="展开衣橱侧栏"
            class="absolute top-4 left-4 z-30 inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-white hover:bg-black hover:text-white border border-black/15 shadow-sm text-[12px] font-medium text-black transition"
          >
            <AppIcon name="panel-left-open" :size="14" />
            衣橱
          </button>

          <!-- Floating Right Reopen Trigger when Right Sidebar is Collapsed -->
          <button
            v-if="!rightOpen"
            type="button"
            @click="rightOpen = true"
            title="展开属性与图层侧栏"
            class="absolute top-4 right-4 z-30 inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-white hover:bg-black hover:text-white border border-black/15 shadow-sm text-[12px] font-medium text-black transition"
          >
            属性与图层
            <AppIcon name="panel-right-open" :size="14" />
          </button>
          <!-- Scaled Editorial Artboard -->
          <div
            ref="artboardRef"
            @click.self="selectedKey = null"
            @pointermove="onBoardPointerMove"
            @pointerup="onBoardPointerUp"
            @pointercancel="onBoardPointerUp"
            class="relative rounded-[28px] shadow-[0_24px_60px_rgba(0,0,0,0.10)] border border-black/[0.08] overflow-hidden shrink-0 touch-none transition-colors duration-200"
            :style="{
              width: `${artboardDimensions.width * boardZoom}px`,
              height: `${artboardDimensions.height * boardZoom}px`,
              backgroundColor: activeBackdrop.bg,
              backgroundImage: activeBackdrop.dot
                ? 'radial-gradient(rgba(0, 0, 0, 0.10) 1.1px, transparent 0)'
                : 'none',
              backgroundSize: '24px 24px',
            }"
          >
            <!-- Subtle Studio Watermark -->
            <div
              class="pointer-events-none absolute top-4 left-5 text-[10px] font-mono uppercase tracking-[0.2em]"
              :class="activeBackdrop.dark ? 'text-white/30' : 'text-black/25'"
            >
              ALTA FLAT-LAY STUDIO · {{ aspectRatio }}
            </div>

            <!-- Magnetic Snap Alignment Guides -->
            <div
              v-if="snapGuideX !== null"
              class="pointer-events-none absolute top-0 bottom-0 border-l border-dashed z-[60]"
              :class="activeBackdrop.dark ? 'border-white/45' : 'border-black/35'"
              :style="{ left: `${snapGuideX}%` }"
            />
            <div
              v-if="snapGuideY !== null"
              class="pointer-events-none absolute left-0 right-0 border-t border-dashed z-[60]"
              :class="activeBackdrop.dark ? 'border-white/45' : 'border-black/35'"
              :style="{ top: `${snapGuideY}%` }"
            />

            <!-- Interactive Garment & Typography Nodes -->
            <div
              v-for="node in nodes"
              :key="node.nodeKey"
              :data-studio-node="node.nodeKey"
              @pointerdown.stop="startMove($event, node)"
              @click.stop="selectedKey = node.nodeKey"
              @wheel="onNodeWheel($event, node)"
              class="absolute cursor-grab active:cursor-grabbing"
              :style="{
                left: `${node.x}%`,
                top: `${node.y}%`,
                zIndex: node.zIndex,
                transform: `translate(-50%, -50%) scale(${node.scale * boardZoom}) rotate(${node.rotation}deg)`,
              }"
            >
              <!-- Typography Sticker Node -->
              <div
                v-if="node.labelText"
                class="relative px-4 py-2 rounded-xl font-mono text-[16px] font-semibold tracking-[0.18em] whitespace-nowrap"
                :class="[
                  activeBackdrop.dark ? 'text-white' : 'text-black',
                  selectedKey === node.nodeKey ? 'ring-1.5 ring-black bg-white/50 backdrop-blur-[2px]' : '',
                ]"
                :style="{ transform: node.flipX ? 'scaleX(-1)' : 'none' }"
              >
                {{ node.labelText }}
              </div>

              <!-- Garment / Accessory Cutout Node -->
              <div
                v-else
                class="relative w-48 h-48 sm:w-56 sm:h-56 p-2 rounded-2xl flex items-center justify-center transition"
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
                    class="w-full h-full drop-shadow-[0_14px_22px_rgba(0,0,0,0.12)]"
                  />
                </div>

                <!-- On-Canvas Figma/Polyvore Transform Handles when selected -->
                <template v-if="selectedKey === node.nodeKey">
                  <!-- Top Rotate Handle -->
                  <button
                    type="button"
                    @pointerdown.stop="startRotateHandle($event, node)"
                    title="按住拖拽自由旋转"
                    class="absolute -top-8 left-1/2 -translate-x-1/2 w-6 h-6 rounded-full bg-black text-white shadow-md flex items-center justify-center cursor-ew-resize"
                  >
                    <AppIcon name="rotate-cw" :size="11" />
                  </button>

                  <!-- Top-Right Quick Delete -->
                  <button
                    type="button"
                    @pointerdown.stop
                    @click.stop="removeNode(node.nodeKey)"
                    title="从画板移除 (Delete)"
                    class="absolute -top-2.5 -right-2.5 w-6 h-6 rounded-full bg-black text-white hover:bg-neutral-700 shadow flex items-center justify-center"
                  >
                    <AppIcon name="x" :size="12" />
                  </button>

                  <!-- Bottom-Right Scale Handle -->
                  <button
                    type="button"
                    @pointerdown.stop="startScaleHandle($event, node)"
                    title="按住拖拽无级缩放（也可用鼠标滚轮）"
                    class="absolute -bottom-2.5 -right-2.5 w-6 h-6 rounded-full bg-white border-2 border-black text-black shadow flex items-center justify-center cursor-nwse-resize"
                  >
                    <AppIcon name="maximize" :size="10" />
                  </button>
                </template>
              </div>
            </div>
          </div>

          <!-- Bottom Shortcut Hint Pill -->
          <div class="hidden md:flex items-center gap-3 px-4 py-1.5 rounded-full bg-white/90 backdrop-blur border border-black/10 text-[11px] text-[#666666] fixed bottom-4 left-1/2 -translate-x-1/2 shadow-sm pointer-events-none">
            <span>拖拽移动（自动磁吸中线）</span>
            <span>·</span>
            <span>滚轮 / 右下角缩放</span>
            <span>·</span>
            <span>顶部手柄旋转</span>
            <span>·</span>
            <span class="font-mono">Ctrl+Z 撤销 · Delete 移除 · Esc 退出</span>
          </div>
        </main>

        <!-- RIGHT PANE: Precision Inspector, Backdrops & Visual Layer Stack -->
        <aside
          v-if="rightOpen"
          class="relative w-64 sm:w-72 border-l border-black/[0.08] bg-white flex flex-col shrink-0 min-h-0 z-20"
        >
          <!-- Edge Collapse Handle (Middle Left of Right Sidebar) -->
          <button
            type="button"
            @click="rightOpen = false"
            title="收起属性与图层侧栏"
            class="absolute top-1/2 -left-3 -translate-y-1/2 z-30 w-6 h-12 rounded-full bg-white hover:bg-black hover:text-white border border-black/15 shadow-sm flex items-center justify-center text-[#555555] transition"
          >
            <AppIcon name="chevron-right" :size="13" />
          </button>

          <div class="flex-1 overflow-y-auto flex flex-col">
          <!-- Section 1: Selected Element Inspector -->
          <div class="p-4 border-b border-black/[0.07] space-y-3">
            <div class="flex items-center justify-between">
              <span class="text-[11px] font-mono uppercase tracking-wider text-[#777777]">选中单品属性</span>
              <div class="flex items-center gap-1.5">
                <span v-if="selectedNode" class="font-mono text-[11px] text-black">层 #{{ selectedNode.zIndex }}</span>
                <button
                  type="button"
                  @click="rightOpen = false"
                  title="收起右侧属性栏"
                  class="w-7 h-7 rounded-full hover:bg-[#F2F2F2] flex items-center justify-center text-[#555555] hover:text-black transition"
                >
                  <AppIcon name="panel-right-close" :size="15" />
                </button>
              </div>
            </div>

            <template v-if="selectedNode && selectedVisual">
              <div class="p-2.5 rounded-2xl bg-[#F7F7F7] flex items-center justify-between gap-2">
                <p class="text-[12.5px] font-semibold text-black truncate">{{ selectedVisual.name }}</p>
                <button
                  type="button"
                  @click="removeNode(selectedNode.nodeKey)"
                  class="text-[11px] text-[#666666] hover:text-black shrink-0"
                >
                  移除
                </button>
              </div>

              <!-- Editable Label Text if Typography Node -->
              <div v-if="selectedNode.labelText !== undefined" class="space-y-1">
                <label class="text-[11px] text-[#666666]">文字内容</label>
                <input
                  v-model="selectedNode.labelText"
                  @change="pushHistory(nodes)"
                  type="text"
                  class="w-full px-3 py-1.5 rounded-xl bg-[#F5F5F5] text-[12px] font-mono outline-none focus:ring-1 focus:ring-black"
                />
              </div>

              <!-- Scale Slider -->
              <div class="space-y-1">
                <div class="flex justify-between text-[11px]">
                  <span class="text-[#666666]">尺寸缩放</span>
                  <span class="font-mono text-black">{{ Math.round(selectedNode.scale * 100) }}%</span>
                </div>
                <input
                  v-model.number="selectedNode.scale"
                  @change="pushHistory(nodes)"
                  type="range"
                  min="0.35"
                  max="2.2"
                  step="0.02"
                  class="w-full accent-black"
                />
              </div>

              <!-- Rotation Slider -->
              <div class="space-y-1">
                <div class="flex justify-between text-[11px]">
                  <span class="text-[#666666]">旋转角度</span>
                  <button type="button" @click="selectedNode.rotation = 0; pushHistory(nodes)" class="font-mono text-black hover:underline">
                    {{ selectedNode.rotation }}° (归零)
                  </button>
                </div>
                <input
                  v-model.number="selectedNode.rotation"
                  @change="pushHistory(nodes)"
                  type="range"
                  min="-90"
                  max="90"
                  step="1"
                  class="w-full accent-black"
                />
              </div>

              <!-- Quick Transform Actions: Flip / Duplicate / Bring Front / Send Back -->
              <div class="grid grid-cols-2 gap-1.5 pt-1 text-[11.5px]">
                <button
                  type="button"
                  @click="toggleFlipX"
                  class="py-1.5 px-2.5 rounded-xl border border-black/10 hover:border-black flex items-center justify-center gap-1 font-medium transition"
                  :class="selectedNode.flipX ? 'bg-black text-white' : 'bg-white text-black'"
                >
                  <AppIcon name="flip" :size="13" />
                  水平镜像
                </button>
                <button
                  type="button"
                  @click="duplicateSelected"
                  class="py-1.5 px-2.5 rounded-xl border border-black/10 bg-white hover:border-black text-black flex items-center justify-center gap-1 font-medium transition"
                >
                  <AppIcon name="copy" :size="13" />
                  复制一份
                </button>
                <button
                  type="button"
                  @click="bringToFront(selectedNode)"
                  class="py-1.5 px-2.5 rounded-xl border border-black/10 bg-white hover:border-black text-black font-medium transition"
                >
                  置于顶层
                </button>
                <button
                  type="button"
                  @click="sendToBack(selectedNode)"
                  class="py-1.5 px-2.5 rounded-xl border border-black/10 bg-white hover:border-black text-black font-medium transition"
                >
                  塞入底层
                </button>
              </div>

              <button
                v-if="selectedVisual.slot && !selectedVisual.slot.anchor"
                type="button"
                @click="emit('swap-slot', selectedVisual.slot.slotKey, selectedVisual.slot.item.id)"
                class="w-full py-2 rounded-xl bg-[#F5F5F5] hover:bg-black hover:text-white text-[12px] font-medium transition flex items-center justify-center gap-1.5"
              >
                <AppIcon name="refresh" :size="12" />
                从衣橱智能换一件同品类
              </button>
            </template>

            <p v-else class="text-[12px] text-[#888888] py-3">
              点击画布中的任意单品，即可无级缩放、镜像翻转、调整叠穿层级或替换款式。
            </p>
          </div>

          <!-- Section 2: Studio Paper Backdrops -->
          <div class="p-4 border-b border-black/[0.07] space-y-2.5">
            <span class="text-[11px] font-mono uppercase tracking-wider text-[#777777]">画布纸张底纹</span>
            <div class="grid grid-cols-5 gap-1.5">
              <button
                v-for="bd in BACKDROPS"
                :key="bd.id"
                type="button"
                @click="backdropId = bd.id"
                :title="bd.label"
                class="flex flex-col items-center gap-1 group"
              >
                <span
                  class="w-9 h-9 rounded-full border transition shadow-sm"
                  :class="backdropId === bd.id ? 'ring-2 ring-black ring-offset-2 border-black/30' : 'border-black/15'"
                  :style="{
                    backgroundColor: bd.bg,
                    backgroundImage: bd.dot ? 'radial-gradient(rgba(0,0,0,0.22) 1px, transparent 0)' : 'none',
                    backgroundSize: '6px 6px',
                  }"
                />
                <span class="text-[10px] text-[#666666] group-hover:text-black">{{ bd.label.slice(0, 2) }}</span>
              </button>
            </div>
          </div>

          <!-- Section 3: Visual Layer Stack (Top -> Bottom) -->
          <div class="p-4 flex-1 space-y-2">
            <div class="flex items-center justify-between">
              <span class="text-[11px] font-mono uppercase tracking-wider text-[#777777]">叠穿图层栈 (顶 → 底)</span>
              <span class="font-mono text-[10.5px] text-[#888888]">{{ nodes.length }}</span>
            </div>

            <div class="space-y-1.5">
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
                  <div class="min-w-0">
                    <p class="text-[11.5px] font-medium text-black truncate">{{ resolveNodeVisual(layer).name }}</p>
                    <p class="text-[10px] font-mono text-[#888888]">Z-{{ layer.zIndex }}</p>
                  </div>
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
          </div>
          </div>
        </aside>
      </div>
    </div>
  </Teleport>
</template>
