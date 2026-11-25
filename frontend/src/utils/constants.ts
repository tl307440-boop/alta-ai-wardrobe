export const CATEGORY_OPTIONS = [
  { key: 'outerwear', label: '外套', barColor: '#111111' },
  { key: 'top', label: '上衣', barColor: '#4B5563' },
  { key: 'bottom', label: '下装', barColor: '#78716C' },
  { key: 'shoes', label: '鞋履', barColor: '#A3A3A3' },
  { key: 'accessory', label: '包配', barColor: '#D4D4D4' },
];

export const SEASON_OPTIONS = ['春', '夏', '秋', '冬'];

export const COLOR_SWATCHES = [
  { name: '米白', hex: '#F3EDE2', light: true },
  { name: '纯白', hex: '#FFFFFF', light: true },
  { name: '燕麦', hex: '#DFD3C3', light: true },
  { name: '卡其', hex: '#C7B299', light: true },
  { name: '焦糖棕', hex: '#8A5230', light: false },
  { name: '牛仔蓝', hex: '#3B5372', light: false },
  { name: '雾霾蓝', hex: '#6F869C', light: false },
  { name: '炭黑', hex: '#262628', light: false },
];

export const ALL_STYLE_OPTIONS = [
  '极简通勤',
  '温柔韩系',
  '美拉德复古',
  'Clean Fit',
  '学院风',
  '法式松弛',
];

export const DISLIKED_COLOR_OPTIONS = ['荧光绿', '亮芭比粉', '电光紫', '高饱和橙'];

export const CITY_OPTIONS = [
  '武汉', '北京', '上海', '杭州', '成都', '广州', '深圳',
  '南京', '西安', '重庆', '长沙', '苏州', '厦门', '青岛', '哈尔滨', '三亚',
];

export const WEATHER_PRESETS = [
  { id: 'cool', label: '微凉多云', condition: '多云', temp: 18, icon: 'cloud-sun', tip: '适宜叠穿', seasonHint: '秋' },
  { id: 'warm', label: '晴暖初夏', condition: '晴朗', temp: 26, icon: 'sun', tip: '轻薄透气', seasonHint: '夏' },
  { id: 'cold', label: '降温小雨', condition: '小雨', temp: 9, icon: 'cloud-rain', tip: '保暖防风', seasonHint: '冬' },
];

export const QUICK_SCENARIOS = [
  { label: '互联网面试', query: '明天去互联网公司面试，想要精神但别太死板的搭配' },
  { label: '周末咖啡厅', query: '周末去咖啡厅和朋友拍照，想要松弛复古氛围' },
  { label: '约会温柔风', query: '周末约会，想要温柔有气质的韩系燕麦风穿搭' },
];

export const REFINE_CHIPS = [
  '把裤子换成黑色阔腿裤',
  '预算降到 200 元以内',
  '换一双更休闲的鞋',
];

export const SCAN_STEPS = ['图片校验', '智能抠图', '特征提取', '属性结构化', '人工复核'];

export const SCAN_SAMPLES = [
  {
    id: 'smp-1',
    shortLabel: '美拉德麂皮夹克',
    sourceLabel: '实物杂乱背景图',
    sourceType: 'photo',
    svgType: 'suede_jacket',
    name: '美拉德焦糖翻毛麂皮夹克',
    category: 'outerwear',
    color: '焦糖棕',
    colorHex: '#8A5230',
    seasons: ['秋', '冬'],
    styleTags: ['美拉德复古', '工装休闲'],
  },
  {
    id: 'smp-2',
    shortLabel: '条纹海魂衫',
    sourceLabel: '淘宝订单截图',
    sourceType: 'screenshot',
    svgType: 'striped_shirt',
    name: '法式复古条纹海魂衫长袖',
    category: 'top',
    color: '牛仔蓝',
    colorHex: '#3B5372',
    seasons: ['春', '秋'],
    styleTags: ['法式松弛', 'Clean Fit', '百搭'],
  },
  {
    id: 'smp-3',
    shortLabel: '雾霾蓝阔腿裤',
    sourceLabel: '实物杂乱背景图',
    sourceType: 'photo',
    svgType: 'trousers',
    name: '雾霾蓝高腰垂感阔腿裤',
    category: 'bottom',
    color: '雾霾蓝',
    colorHex: '#6F869C',
    seasons: ['春', '夏', '秋'],
    styleTags: ['极简通勤', '温柔韩系'],
  },
];

export const INSPIRATION_PRESETS = [
  {
    id: 'insp-1',
    sourceTag: '小红书高赞街拍 · 韩系通勤',
    title: '首尔秋日 · 短风衣松弛通勤 Look',
    subtitle: '风衣叠穿白T + 直筒牛仔 + 焦糖乐福鞋',
    defaultQuery: '帮我找图中这件风衣的相似款，预算 300 元以内，先看看我的衣橱里有没有',
  },
  {
    id: 'insp-2',
    sourceTag: '抖音复古穿搭 · 美拉德氛围',
    title: '秋日咖啡馆 · 美拉德复古学院风',
    subtitle: '麂皮工装夹克 + 条纹打底 + 黑色阔腿裤 + 德训鞋',
    defaultQuery: '帮我拆解这套美拉德穿搭，预算 300 元以内，优先复用我的衣橱单品',
  },
];

export const AI_PROVIDER_PRESETS = [
  {
    id: 'openai-relay',
    label: 'OpenAI 兼容中转站 (New API / Miau)',
    baseUrl: 'https://api.openai.com/v1',
    visionModel: 'gpt-4o',
    chatModel: 'gpt-4o-mini',
    imageModel: 'gpt-image-1',
  },
  {
    id: 'dashscope',
    label: '通义千问 (DashScope)',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    visionModel: 'qwen-vl-max',
    chatModel: 'qwen-plus',
    imageModel: 'wanx2.1-t2i-turbo',
  },
  {
    id: 'zhipu',
    label: '智谱 AI (GLM-4V)',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    visionModel: 'glm-4v-plus',
    chatModel: 'glm-4-flash',
    imageModel: 'cogview-3-flash',
  },
  {
    id: 'openai',
    label: 'OpenAI 官方',
    baseUrl: 'https://api.openai.com/v1',
    visionModel: 'gpt-4o',
    chatModel: 'gpt-4o-mini',
    imageModel: 'gpt-image-1',
  },
  {
    id: 'gemini',
    label: 'Google Gemini (兼容协议)',
    baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai',
    visionModel: 'gemini-2.5-flash',
    chatModel: 'gemini-2.5-flash',
    imageModel: 'gpt-image-1',
  },
  {
    id: 'deepseek',
    label: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com/v1',
    visionModel: 'deepseek-chat',
    chatModel: 'deepseek-chat',
    imageModel: 'gpt-image-1',
  },
];

export function categoryLabel(key: string): string {
  return CATEGORY_OPTIONS.find((c) => c.key === key)?.label || key;
}

export function categoryBarColor(key: string): string {
  return CATEGORY_OPTIONS.find((c) => c.key === key)?.barColor || '#C7B299';
}

export function platformBadgeClass(platform: string): string {
  if (platform === 'taobao') return 'bg-[#FFF3ED] text-[#FF5000] border border-[#FF5000]/20';
  if (platform === 'jd') return 'bg-[#FEF0F0] text-[#E1251B] border border-[#E1251B]/20';
  return 'bg-[#F5F5F5] text-black border border-black/10';
}
