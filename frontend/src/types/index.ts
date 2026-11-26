export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
}

export interface UserInfo {
  id: number;
  username: string;
  nickname: string;
  avatarUrl?: string;
  email?: string;
  wardrobeCount: number;
  historyCount: number;
  favoriteCount: number;
}

export interface AuthResponse {
  token: string;
  expiresIn: number;
  userInfo: UserInfo;
}

export interface WardrobeItem {
  id: number;
  userId?: number;
  name: string;
  category: string;
  subCategory?: string;
  color: string;
  colorHex: string;
  seasons: string[];
  thickness?: number;
  styleTags: string[];
  material?: string;
  svgType?: string;
  originalImageUrl?: string;
  processedImageUrl?: string;
  sourceType?: string;
  isManuallyCorrected?: boolean;
  price?: number | null;
  costPerWear?: number | null;
  wearCount: number;
  lastWornDate?: string;
  remark?: string;
  createdAt?: string;
}

export interface CategoryStatItem {
  key: string;
  label: string;
  count: number;
  percent: number;
}

export interface StyleAffinityItem {
  name: string;
  score: number;
}

export interface ColorStatItem {
  color: string;
  hex: string;
  count: number;
  percent: number;
}

export interface ColorPairItem {
  colorA: string;
  hexA: string;
  colorB: string;
  hexB: string;
  count: number;
}

export interface MirrorPieceItem {
  id: number;
  name: string;
  category: string;
  color: string;
  colorHex: string;
  wearCount: number;
  price?: number | null;
  costPerWear?: number | null;
  imageUrl?: string | null;
  svgType?: string | null;
}

export interface PreferenceAnalytics {
  defaultCity: string;
  favoriteStyles: string[];
  favoriteColors: string[];
  dislikedColors: string[];
  preferredScenes: string[];
  budgetLimit: number;
  bodyShape?: string;
  colorSeason?: string;
  heightCm?: number;
  bodyShapeAdvice?: string;
  colorSeasonAdvice?: string;
  seasonRecommendedColors?: string[];
  styleWeights: Record<string, number>;
  categoryStats: CategoryStatItem[];
  styleAffinity: StyleAffinityItem[];
  colorStats: ColorStatItem[];
  colorPairFingerprints?: ColorPairItem[];
  coreStaples?: MirrorPieceItem[];
  neglectedPieces?: MirrorPieceItem[];
  idleRate: number;
  totalWardrobeValue?: number;
  avgCostPerWear?: number | null;
  pricedItemCount?: number;
  bestValueItemName?: string | null;
  bestValueCpw?: number | null;
  highestCpwItemName?: string | null;
  highestCpw?: number | null;
}

export interface AiModelConfig {
  providerName: string;
  baseUrl: string;
  hasApiKey: boolean;
  apiKeyMasked: string;
  visionModel: string;
  chatModel: string;
  imageModel?: string;
  modelReferenceUrl?: string | null;
  enabled: boolean;
  serverDefaultAvailable: boolean;
  vtonEnabled?: boolean;
  vtonProvider?: string;
  vtonModel?: string;
  hasVtonApiKey?: boolean;
  vtonApiKeyMasked?: string;
  vtonKeyReusesMain?: boolean;
  vtonPublicBaseUrl?: string | null;
  updatedAt?: string;
}

export interface AiTestResult {
  success: boolean;
  latencyMs: number;
  modelReply?: string;
  message: string;
}

export interface WeatherInfo {
  city: string;
  temp: number;
  apparentTemp?: number;
  humidity?: number;
  windSpeed?: number;
  condition: string;
  icon: string;
  tip: string;
  seasonHint?: string;
  source?: string;
  updatedAt?: string;
  live?: boolean;
}

export interface ProductItem {
  id: number;
  title: string;
  category: string;
  color: string;
  colorHex: string;
  svgType?: string;
  styleTags: string[];
  price: number;
  platform: string;
  platformLabel: string;
  imageUrl?: string;
  productUrl: string;
  description: string;
}

export interface MissingSlot {
  category: string;
  name: string;
  color: string;
  colorHex: string;
  svgType?: string;
  keywords?: string;
  reason?: string;
}

export interface PlanSlot {
  slotKey: string;
  slotLabel: string;
  missing: boolean;
  anchor: boolean;
  item: WardrobeItem;
}

export interface CollageNode {
  nodeKey: string;
  slotKey: string;
  itemId: number;
  x: number;
  y: number;
  scale: number;
  rotation: number;
  zIndex: number;
  flipX?: boolean;
  customName?: string;
  customImageUrl?: string;
  customSvgType?: string;
  customColorHex?: string;
  labelText?: string;
}

export interface VisualPieceFeature {
  slotKey: string;
  slotLabel: string;
  itemId?: number;
  name: string;
  imageUrl?: string;
  colorHex?: string;
  svgType?: string;
  zhSummary: string;
  enPrompt: string;
  visualGrounded: boolean;
  vtonApplied?: boolean;
}

export interface TryOnVisualReport {
  usedRealVlm: boolean;
  referencedGarmentCount: number;
  usedGarmentImagesInGen: boolean;
  engineType?: 'dashscope_vton' | 'general_image' | 'editorial_fallback' | string;
  vtonFallbackReason?: string | null;
  overallPalette: string;
  bodyProfile: string;
  sceneLabel: string;
  pieces: VisualPieceFeature[];
}

export interface TryOnResult {
  planId: number;
  previewImageUrl: string;
  usedRealImageModel: boolean;
  usedReferencePhoto: boolean;
  modelUsed?: string;
  endpointUsed?: string;
  promptUsed: string;
  notice?: string;
  visualReport?: TryOnVisualReport | null;
}

export interface TryOnJob {
  id: number;
  planId: number;
  planThemeTitle?: string | null;
  status: 'QUEUED' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED';
  stylePreset: string;
  generationMode: string;
  useReferencePhoto: boolean;
  usedRealImageModel: boolean;
  usedReferencePhoto: boolean;
  modelUsed?: string | null;
  endpointUsed?: string | null;
  promptUsed?: string | null;
  previewImageUrl?: string | null;
  notice?: string | null;
  errorMessage?: string | null;
  visualReport?: TryOnVisualReport | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface OutfitPlanVo {
  id: number;
  themeTitle: string;
  triggerType: string;
  sceneTag: string;
  weatherSnapshot?: WeatherInfo;
  userQuery?: string;
  slots: PlanSlot[];
  missingItems: MissingSlot[];
  recommendedProducts: ProductItem[];
  aiReasoning: string;
  matchScore: number;
  collageLayout?: CollageNode[];
  tryOnImageUrl?: string | null;
  favorite: boolean;
  usedRealLlm: boolean;
  usedVisualReview?: boolean;
  createdAt?: string;
}

export interface SuggestedSwap {
  itemId: number;
  itemName: string;
  category: string;
  reason: string;
}

export interface OutfitCritiqueResult {
  overallScore: number;
  colorScore: number;
  proportionScore: number;
  sceneScore: number;
  headline: string;
  highlights: string[];
  improvements: string[];
  suggestedSwap?: SuggestedSwap | null;
  usedRealVlm: boolean;
  modelUsed?: string;
  notice?: string;
}

export interface HistoryRecord {
  id: number;
  planId?: number;
  themeTitle: string;
  wearDate: string;
  sceneTag: string;
  weatherInfo: string;
  weatherTemp: number;
  weatherIcon: string;
  itemIds: number[];
  items: WardrobeItem[];
  palette: string[];
  aiReasoning: string;
  ootdPhotoUrl?: string | null;
  feedbackScore: number;
  notes: string;
}

export interface ItemDetailResponse {
  item: WardrobeItem;
  relatedHistory: HistoryRecord[];
}

export interface ItemRecognitionResult {
  clothing: boolean;
  name: string;
  category: string;
  subCategory?: string;
  color: string;
  colorHex: string;
  seasons: string[];
  thickness: number;
  styleTags: string[];
  material?: string;
  svgType?: string;
  originalImageUrl: string;
  processedImageUrl: string;
  cutoutFallback: boolean;
  aiRawResult?: string;
  confidence: number;
  usedRealLlm: boolean;
  notice?: string;
}

/** Fractions of the source image (0..1), origin = top-left. */
export interface BoundingBox {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface DetectedItem {
  name: string;
  category: string;
  subCategory?: string;
  color: string;
  colorHex: string;
  seasons: string[];
  thickness?: number;
  styleTags: string[];
  material?: string;
  svgType: string;
  bbox: BoundingBox | null;
  similarWardrobeName: string | null;
  similarity: number;
  aiRawResult?: string;
}

export interface ItemsDetectionResult {
  originalImageUrl: string | null;
  items: DetectedItem[];
  usedRealLlm: boolean;
  notice?: string;
}

export interface StoredItemImage {
  originalImageUrl: string;
  processedImageUrl: string;
  cutoutFallback: boolean;
}

export interface InspirationItemMatch {
  slotLabel: string;
  name: string;
  category: string;
  color: string;
  colorHex: string;
  svgType?: string;
  styleTags: string[];
  keywords?: string;
  posX: number;
  posY: number;
  matchedWardrobe: WardrobeItem | null;
  similarity: number;
  products: ProductItem[];
}

export interface InspirationAnalyzeResult {
  overallStyle: string;
  inspirationImageUrl?: string;
  items: InspirationItemMatch[];
  usedRealLlm: boolean;
  notice?: string;
}

export interface InspoSimilarProduct {
  id: string;
  brand: string;
  title: string;
  price: number;
  originalPrice?: number | null;
  platform: 'taobao' | 'jd' | 'tmall' | string;
  platformLabel: string;
  imageUrl?: string;
  svgType?: string;
  colorHex?: string;
  buyUrl: string;
  taobaoUrl: string;
  jdUrl: string;
}

export interface InspoLookPiece {
  id: string;
  brand: string;
  name: string;
  category: string;
  slotLabel: string;
  color: string;
  colorHex: string;
  price: number;
  imageUrl?: string;
  svgType?: string;
  taobaoUrl: string;
  jdUrl: string;
  similarItems: InspoSimilarProduct[];
}

export interface InspoLook {
  id: string;
  creatorName: string;
  creatorHandle: string;
  city: string;
  gender: 'womens' | 'mens';
  title: string;
  styleTag: string;
  seasonTag: string;
  viewsText: string;
  viewsCount: number;
  rank: number;
  likesCount: number;
  commentsCount: number;
  restylesCount: number;
  liked: boolean;
  matchScore?: number;
  matchReason?: string;
  modelImageUrl: string;
  fallbackSvgType: string;
  styleTags: string[];
  pieces: InspoLookPiece[];
  sampleComments: string[];
}

export interface InspoBanner {
  id: string;
  eyebrow: string;
  title: string;
  subtitle: string;
  ctaLabel: string;
  brandFilter: string;
  bgStyle: string;
  textTone: 'dark' | 'light';
  imageUrl: string;
}

export interface InspoBrandCollection {
  id: string;
  name: string;
  cnName: string;
  origin: string;
  tagline: string;
  priceBand: string;
  taobaoFlagshipUrl: string;
  jdFlagshipUrl: string;
  lookIds: string[];
  coverImageUrl: string;
}

export interface InspoFeedResponse {
  banners: InspoBanner[];
  looks: InspoLook[];
  brands: InspoBrandCollection[];
  likedCount: number;
}

export interface InspoLikeToggle {
  lookId: string;
  liked: boolean;
  likesCount: number;
}
