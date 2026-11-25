<script setup lang="ts">
import { computed } from 'vue';

const props = withDefaults(
  defineProps<{
    type?: string;
    color?: string;
  }>(),
  {
    type: 'cardigan',
    color: '#EFECE6',
  }
);

function shadeHex(hex: string, percent: number): string {
  if (!hex || !hex.startsWith('#') || hex.length < 7) return '#C8BFB2';
  const num = parseInt(hex.slice(1, 7), 16);
  const r = Math.min(255, Math.max(0, (num >> 16) + percent));
  const g = Math.min(255, Math.max(0, ((num >> 8) & 0x00ff) + percent));
  const b = Math.min(255, Math.max(0, (num & 0x0000ff) + percent));
  return '#' + ((1 << 24) + (r << 16) + (g << 8) + b).toString(16).slice(1);
}

const uid = Math.random().toString(36).slice(2, 8);
const shadowId = `flatShadow-${uid}`;
const stripeId = `stripeClip-${uid}`;

const dark = computed(() => shadeHex(props.color, -26));
const darker = computed(() => shadeHex(props.color, -46));
const light = computed(() => shadeHex(props.color, 20));
const strokeColor = computed(() => {
  const hex = props.color?.startsWith('#') && props.color.length >= 7 ? props.color : '#EFECE6';
  const num = parseInt(hex.slice(1, 7), 16);
  const lum = (num >> 16) * 0.299 + ((num >> 8) & 255) * 0.587 + (num & 255) * 0.114;
  return lum < 75 ? '#484542' : shadeHex(hex, -55);
});
</script>

<template>
  <svg viewBox="0 0 160 160" xmlns="http://www.w3.org/2000/svg" class="select-none overflow-visible">
    <defs>
      <filter :id="shadowId" x="-15%" y="-15%" width="130%" height="130%">
        <feDropShadow dx="0" dy="5" stdDeviation="4" flood-color="#2D241A" flood-opacity="0.11" />
      </filter>
    </defs>

    <g v-if="type === 'cardigan'" :filter="`url(#${shadowId})`">
      <path d="M56 26 L30 38 L14 110 L32 114 L44 62 L44 134 L116 134 L116 62 L128 114 L146 110 L130 38 L104 26 L80 68 Z" :fill="color" :stroke="strokeColor" stroke-width="1.4" stroke-linejoin="round" />
      <path d="M56 26 Q80 34 104 26 L80 68 Z" :fill="dark" />
      <path d="M56 26 L80 68 L80 134 M104 26 L80 68" fill="none" :stroke="strokeColor" stroke-width="1.3" />
      <rect x="76" y="66" width="8" height="68" :fill="light" opacity="0.55" />
      <rect x="44" y="124" width="72" height="10" :fill="dark" opacity="0.35" />
      <rect x="52" y="96" width="18" height="18" rx="3" :fill="dark" opacity="0.22" :stroke="strokeColor" stroke-width="1" />
      <rect x="90" y="96" width="18" height="18" rx="3" :fill="dark" opacity="0.22" :stroke="strokeColor" stroke-width="1" />
      <circle cx="80" cy="76" r="2.6" fill="#7A5C43" />
      <circle cx="80" cy="92" r="2.6" fill="#7A5C43" />
      <circle cx="80" cy="108" r="2.6" fill="#7A5C43" />
      <circle cx="80" cy="122" r="2.6" fill="#7A5C43" />
    </g>

    <g v-else-if="type === 'tshirt'" :filter="`url(#${shadowId})`">
      <path d="M58 26 Q80 40 102 26 L136 42 L146 72 L120 80 L114 62 L114 134 L46 134 L46 62 L40 80 L14 72 L24 42 Z" :fill="color" :stroke="strokeColor" stroke-width="1.4" stroke-linejoin="round" />
      <path d="M58 26 Q80 42 102 26 Q80 34 58 26 Z" :fill="dark" opacity="0.45" />
      <path d="M56 28 Q80 45 104 28" fill="none" :stroke="strokeColor" stroke-width="1.3" />
      <line x1="47" y1="128" x2="113" y2="128" :stroke="dark" stroke-width="1" stroke-dasharray="2 2" />
    </g>

    <g v-else-if="type === 'striped_shirt'" :filter="`url(#${shadowId})`">
      <defs>
        <clipPath :id="stripeId">
          <path d="M54 28 Q80 38 106 28 L132 40 L146 112 L128 116 L114 64 L114 134 L46 134 L46 64 L32 116 L14 112 L28 40 Z" />
        </clipPath>
      </defs>
      <path d="M54 28 Q80 38 106 28 L132 40 L146 112 L128 116 L114 64 L114 134 L46 134 L46 64 L32 116 L14 112 L28 40 Z" fill="#F8F5EE" :stroke="strokeColor" stroke-width="1.4" stroke-linejoin="round" />
      <g :clip-path="`url(#${stripeId})`">
        <rect v-for="y in [44, 58, 72, 86, 100, 114, 126]" :key="y" x="10" :y="y" width="140" height="5.5" :fill="color" opacity="0.92" />
      </g>
      <path d="M54 28 Q80 38 106 28" fill="none" :stroke="color" stroke-width="2.5" />
    </g>

    <g v-else-if="type === 'knit_vest'" :filter="`url(#${shadowId})`">
      <path d="M54 24 L66 24 L80 58 L94 24 L106 24 L116 32 C112 52 112 66 118 76 L116 134 L44 134 L42 76 C48 66 48 52 44 32 Z" :fill="color" :stroke="strokeColor" stroke-width="1.4" stroke-linejoin="round" />
      <path d="M62 24 L80 64 L98 24" fill="none" :stroke="light" stroke-width="2.2" opacity="0.45" />
      <rect x="44" y="122" width="72" height="12" :fill="light" opacity="0.16" />
    </g>

    <g v-else-if="type === 'jeans'" :filter="`url(#${shadowId})`">
      <path d="M46 20 L114 20 L120 142 L90 142 L80 66 L70 142 L40 142 Z" :fill="color" :stroke="strokeColor" stroke-width="1.4" stroke-linejoin="round" />
      <rect x="46" y="20" width="68" height="10" :fill="dark" opacity="0.5" />
      <circle cx="80" cy="25" r="2.2" fill="#C9A87C" />
      <path d="M80 30 L80 56 Q80 61 75 61" fill="none" stroke="#D2A673" stroke-width="1.2" />
      <path d="M46 42 Q60 44 62 30 M114 42 Q100 44 98 30" fill="none" stroke="#D2A673" stroke-width="1.2" />
    </g>

    <g v-else-if="type === 'trousers'" :filter="`url(#${shadowId})`">
      <path d="M48 20 L112 20 L124 142 L86 142 L80 64 L74 142 L36 142 Z" :fill="color" :stroke="strokeColor" stroke-width="1.4" stroke-linejoin="round" />
      <rect x="48" y="20" width="64" height="9" :fill="light" opacity="0.15" />
      <line x1="64" y1="29" x2="55" y2="136" :stroke="light" stroke-width="1.2" opacity="0.35" />
      <line x1="96" y1="29" x2="105" y2="136" :stroke="light" stroke-width="1.2" opacity="0.35" />
    </g>

    <g v-else-if="type === 'pleated_skirt'" :filter="`url(#${shadowId})`">
      <path d="M52 28 L108 28 L134 132 Q80 140 26 132 Z" :fill="color" :stroke="strokeColor" stroke-width="1.4" stroke-linejoin="round" />
      <rect x="52" y="28" width="56" height="10" :fill="dark" opacity="0.45" />
      <path d="M58 38 L42 133 M68 38 L58 135 M80 38 L80 136 M92 38 L102 135 M102 38 L118 133" :stroke="darker" stroke-width="1.2" opacity="0.55" />
    </g>

    <g v-else-if="type === 'trench'" :filter="`url(#${shadowId})`">
      <path d="M54 20 L106 20 L134 36 L146 112 L128 116 L116 62 L122 140 L38 140 L44 62 L32 116 L14 112 L26 36 Z" :fill="color" :stroke="strokeColor" stroke-width="1.4" stroke-linejoin="round" />
      <path d="M54 20 L46 46 L72 52 Z M106 20 L114 46 L88 52 Z" :fill="light" opacity="0.5" :stroke="strokeColor" stroke-width="1" />
      <circle cx="68" cy="64" r="2.6" fill="#5C4433" />
      <circle cx="92" cy="64" r="2.6" fill="#5C4433" />
      <circle cx="68" cy="80" r="2.6" fill="#5C4433" />
      <circle cx="92" cy="80" r="2.6" fill="#5C4433" />
      <rect x="41" y="90" width="78" height="8" rx="2" :fill="dark" :stroke="strokeColor" stroke-width="1" />
      <rect x="74" y="88" width="12" height="12" rx="2" fill="none" stroke="#5C4433" stroke-width="2.2" />
    </g>

    <g v-else-if="type === 'blazer'" :filter="`url(#${shadowId})`">
      <path d="M54 22 L106 22 L134 36 L144 112 L126 116 L115 62 L116 134 L44 134 L45 62 L34 116 L16 112 L26 36 Z" :fill="color" :stroke="strokeColor" stroke-width="1.4" stroke-linejoin="round" />
      <path d="M56 22 L48 54 L66 58 L80 78 M104 22 L112 54 L94 58 L80 78" :fill="light" opacity="0.55" :stroke="strokeColor" stroke-width="1" />
      <line x1="80" y1="78" x2="80" y2="134" :stroke="strokeColor" stroke-width="1.2" />
      <circle cx="80" cy="86" r="2.8" fill="#52463B" />
      <rect x="52" y="102" width="19" height="5" rx="1" :fill="dark" opacity="0.5" />
      <rect x="89" y="102" width="19" height="5" rx="1" :fill="dark" opacity="0.5" />
    </g>

    <g v-else-if="type === 'suede_jacket'" :filter="`url(#${shadowId})`">
      <path d="M56 26 L104 26 L134 40 L144 112 L126 116 L116 64 L116 128 L44 128 L44 64 L34 116 L16 112 L26 40 Z" :fill="color" :stroke="strokeColor" stroke-width="1.4" stroke-linejoin="round" />
      <path d="M56 26 L48 44 L78 40 L80 34 L82 40 L112 44 L104 26 Z" :fill="dark" :stroke="strokeColor" stroke-width="1.2" />
      <line x1="80" y1="36" x2="80" y2="128" :stroke="darker" stroke-width="2" />
      <rect x="52" y="54" width="20" height="22" rx="2" :fill="dark" opacity="0.3" :stroke="strokeColor" stroke-width="1" />
      <rect x="88" y="54" width="20" height="22" rx="2" :fill="dark" opacity="0.3" :stroke="strokeColor" stroke-width="1" />
      <rect x="44" y="118" width="72" height="10" :fill="dark" opacity="0.45" />
    </g>

    <g v-else-if="type === 'loafers'" :filter="`url(#${shadowId})`">
      <g transform="translate(12, 6)">
        <path d="M28 40 C26 24 44 20 52 28 C60 36 58 70 56 96 C54 118 46 128 36 126 C26 124 22 108 24 88 C26 68 30 56 28 40 Z" :fill="color" :stroke="strokeColor" stroke-width="1.4" />
        <ellipse cx="40" cy="84" rx="10" ry="22" fill="#2A1D15" opacity="0.75" />
        <path d="M26 54 Q40 49 56 54 L55 62 Q40 58 27 62 Z" :fill="dark" :stroke="strokeColor" stroke-width="1" />
      </g>
      <g transform="translate(76, 16)">
        <path d="M28 40 C26 24 44 20 52 28 C60 36 58 70 56 96 C54 118 46 128 36 126 C26 124 22 108 24 88 C26 68 30 56 28 40 Z" :fill="color" :stroke="strokeColor" stroke-width="1.4" />
        <ellipse cx="40" cy="84" rx="10" ry="22" fill="#2A1D15" opacity="0.75" />
        <path d="M26 54 Q40 49 56 54 L55 62 Q40 58 27 62 Z" :fill="dark" :stroke="strokeColor" stroke-width="1" />
      </g>
    </g>

    <g v-else-if="type === 'sneakers'" :filter="`url(#${shadowId})`">
      <g transform="translate(8, 12)">
        <path d="M20 58 L54 32 L78 44 L126 52 C136 54 140 64 134 70 L20 70 Z" :fill="color" :stroke="strokeColor" stroke-width="1.4" stroke-linejoin="round" />
        <path d="M18 68 L136 68 L134 76 L20 76 Z" fill="#C49A6C" stroke="#8F6A42" stroke-width="1.2" />
        <path d="M104 49 L134 55 C138 60 136 68 130 68 L98 68 Z" :fill="dark" opacity="0.55" />
      </g>
      <g transform="translate(152, 74) scale(-1, 1)">
        <path d="M20 58 L54 32 L78 44 L126 52 C136 54 140 64 134 70 L20 70 Z" :fill="color" :stroke="strokeColor" stroke-width="1.4" stroke-linejoin="round" />
        <path d="M18 68 L136 68 L134 76 L20 76 Z" fill="#C49A6C" stroke="#8F6A42" stroke-width="1.2" />
        <path d="M104 49 L134 55 C138 60 136 68 130 68 L98 68 Z" :fill="dark" opacity="0.55" />
      </g>
    </g>

    <g v-else-if="type === 'tote_bag'" :filter="`url(#${shadowId})`">
      <path d="M56 62 C56 26 104 26 104 62" fill="none" stroke="#7A4928" stroke-width="6" stroke-linecap="round" />
      <path d="M32 58 L128 58 L120 138 L40 138 Z" :fill="color" :stroke="strokeColor" stroke-width="1.4" stroke-linejoin="round" />
      <path d="M38 118 L122 118 L120 138 L40 138 Z" fill="#7A4928" opacity="0.88" />
      <rect x="54" y="58" width="6" height="60" fill="#7A4928" opacity="0.85" />
      <rect x="100" y="58" width="6" height="60" fill="#7A4928" opacity="0.85" />
    </g>

    <g v-else-if="type === 'crossbody_bag'" :filter="`url(#${shadowId})`">
      <path d="M40 72 C40 30 120 30 120 72" fill="none" :stroke="darker" stroke-width="5" stroke-linecap="round" />
      <path d="M34 68 L126 68 L122 114 C120 132 104 138 80 138 C56 138 40 132 38 114 Z" :fill="color" :stroke="strokeColor" stroke-width="1.5" />
      <path d="M34 68 L126 68 L122 96 C108 112 52 112 38 96 Z" :fill="light" opacity="0.22" :stroke="strokeColor" stroke-width="1.3" />
      <rect x="74" y="98" width="12" height="14" rx="3" fill="#D4AF6A" stroke="#8F6E32" stroke-width="1.2" />
    </g>

    <g v-else :filter="`url(#${shadowId})`">
      <path d="M58 26 Q80 40 102 26 L136 42 L146 72 L120 80 L114 62 L114 134 L46 134 L46 62 L40 80 L14 72 L24 42 Z" :fill="color" :stroke="strokeColor" stroke-width="1.4" stroke-linejoin="round" />
    </g>
  </svg>
</template>
