# AGENTS.md — ALTA · 智搭衣橱 (AI Wardrobe) Engineering & UI/UX Constitution

> **Purpose**: This document is the binding engineering and design guide for **ALTA · 智搭衣橱 (基于多模态大模型与个性化推荐的智能衣橱系统)**.
> **Design Identity**: **Alta Monochrome Editorial Gallery（极简纯白画廊 + 液态毛玻璃悬浮 Dock + 中国本土灵感导购闭环）**. Reverse-engineered and adapted from Alta Daily's high-fashion minimalist web aesthetics, combined with Indyx Cost-Per-Wear (CPW) asset analytics and China e-commerce (Taobao / JD) shopping flows.

---

## 1. Project Architecture Overview

- **Repository Layout**:
  - `backend/`: Spring Boot 3.2 (Java 17/21) + MyBatis-Plus + MySQL 8.0 (with `dev` H2 MySQL-mode file DB fallback) + JJWT + BCrypt + AES-256-GCM encrypted user AI config + OpenAI-compatible Multimodal LLM Client + Open-Meteo Weather Client + China Inspo Catalog & Multi-Tenant Like Persistence.
  - `frontend/`: Vue 3 (`<script setup lang="ts">`) + Vite 5 + TypeScript + Tailwind CSS + Pinia + Vue Router 4 + Axios + `@imgly/background-removal` (browser-side AI cutout with graceful fallback).
  - `docker-compose.yml`: One-command MySQL 8.0 service pre-configured with `ai_wardrobe` schema.

---

## 2. UI/UX Design System Invariants (Alta Monochrome Gallery)

When adding features, refactoring components, or fixing bugs, preserve the established **Alta Monochrome Editorial Gallery** aesthetic:

### 2.1 Color Palette (`frontend/tailwind.config.js`)
Avoid generic admin blue templates, heavy colored shadows, or cluttered dark neon panels. Stick to crisp monochrome contrast with surgical platform badges:

| Token | Hex / Value | Usage |
| :--- | :--- | :--- |
| `alta-bg` (`body`) | `#FFFFFF` | Pure white editorial page canvas |
| `alta-surface` / `oat-100` | `#F5F5F5` | Garment image wells, Shop Similar item frames, secondary cards |
| `alta-pill` | `#F2F2F2` | Rounded pill search bar, segmented pill tracks |
| `oat-50` | `#FAFAFA` | Subtle elevated metrics strips & dot-grid flat-lay background |
| `oat-300` / `alta-border` | `#E5E5E5` / `rgba(0,0,0,0.08)` | Hairline `1px` borders and dividers |
| `charcoal` / `alta-ink` | `#000000` | Primary ink, solid pill CTAs, active tabs, `+` circular dock trigger |
| `warmgray` / `alta-muted` | `#666666` | Secondary metadata, subtitles, city captions, view counts |
| `alta-taobao` | `#FF5000` | Taobao platform badge & direct shopping CTA only |
| `alta-jd` | `#E1251B` | JD platform badge & direct shopping CTA only |
| `.alta-glass-dock` | `rgba(255,255,255,0.65)` + `blur(12px) saturate(180%)` | Signature floating Liquid Glass bottom navigation pill |
| `.alta-rank-stroke` | `color: transparent; -webkit-text-stroke: 1.6px #000` | Oversized outlined leaderboard ranking numbers on Inspo Top tab |

### 2.2 Typography Hierarchy
- **Primary Editorial Sans (`font-sans` / `font-serif`)**: `'Inter', 'Noto Sans SC', 'Helvetica Neue', system-ui, sans-serif` — Clean low-weight (`300 / 400 / 500 / 600`) fashion-gallery typography inspired by `NBInternationalPro`.
- **Data Mono (`font-mono`)**: `'JetBrains Mono', monospace` — Prices (`¥299`), CPW (`CPW ¥37.4`), match scores (`96%`), temperatures (`18°C`), view counts (`14.2w`), wear counts, calendar dates.

### 2.3 Navigation Shell & Core Views (`AppLayout.vue`)
Design direction: **minimal, Acloset-style** — few controls per screen, categories laid out flat, details tucked behind a tap. Do not re-introduce dense metric strips, per-card badges or multi-row toolbars.

- **No Left Sidebar**: Keep the centered full-canvas gallery layout (`max-w-[920px]` on `/inspo`, `max-w-[1080px]` on workspace views) with generous bottom breathing room (`pb-28`) for the floating dock.
- **Minimal Sticky Top Header (`h-14`)**: `ALTA · 智搭` wordmark + current route title (e.g. `AI 识别`, `我的`) on the left; on the right only the interactive Weather Capsule and, while a try-on job is running, a black `AI 试穿生成中` pill. There are **no center pill shortcuts** and **no logout button** in the header (logout lives in `我的` → `偏好与设置`).
- **Unified Floating Liquid Glass Bottom Dock (`#alta-floating-dock`)**:
  - Centered `max-w-[540px]` pill with sliding translucent highlight capsule (`25%` width per tab):
    1. **首页 (`/`)** — Hero = **Today's Look** (weather-aware curated outfit, one primary action) → `今日热度造型 TOP 6` China Inspo preview → `近期收藏搭配方案`. No CPW card on the home page.
    2. **衣橱 (`/wardrobe`)** — One-line summary (`共 N 件单品 · …`, link to `/profile?tab=stats`) + flat category pills (`全部 / 外套 / 上衣 / 下装 / 鞋履 / 包配`) + search + a single `筛选与排序` button that opens a bottom filter sheet (color / season / **CPW sort**: `CPW 最低` / `CPW 最高` / `穿着最多`). Cards are plain `4:5` image + name (CPW only appears once a price is recorded); hover/touch shows a small "以此单品为核心搭配" action. `ItemDetailDrawer` keeps editable `price`, live CPW insight and reverse calendar lookup.
    3. **灵感发现 🔍 (`/inspo`)** — China-adapted Alta Inspo experience:
       - Top pill search bar + liked-only heart filter (`POST /api/inspo/looks/{id}/like`).
       - Three underline tabs: `为你推荐` (dual editorial banners + 6-col borderless model wall with China cities), `热度榜` (`女装 / 男装` pill toggle + `.alta-rank-stroke` outlined rank numbers), `品牌精选` (China designer & urban brands with Taobao/JD flagship links).
       - **Layer 2 (`InspoLookModal.vue`)**: Full-height white showcase with top-left `<`, top-right `...`, **left floating vertical piece rail** (click any clothing/jewelry piece), centered full-body model photo, bottom-right vertical social actions (like, comment drawer, one-click restyle into `/lookbook`).
       - **Layer 3 (`InspoPieceModal.vue`)**: `来自 XX 的衣橱` piece hero + direct **Taobao (`s.taobao.com/search`) & JD (`search.jd.com/Search`)** links + `收入我的衣橱` (records `price` for CPW) + 4-column `选购相似同款` grid with Taobao/JD filtering.
    4. **我的 (`/profile`)** — Three underline tabs driven by the URL (`/profile?tab=stats|calendar|settings`, default `stats`; `settings` also accepts `panel=ai` to open the model config):
       - `衣橱统计` — Indyx CPW asset summary, category donut, style affinity, wardrobe palette.
       - `穿搭与收藏` — Calendar overview (this-month / total record counts, latest record) with an entry into the full `/calendar` page, plus favorited outfit plans.
       - `偏好与设置` — Style/color/city/budget preferences, Server AI Model & API Key config card (`AiModelConfigCard.vue`), logout.
  - **Independent `56×56` Black Circular `+` Button**: Toggles a compact floating Action Sheet (no title bar, no × button, no description lines; `role="dialog"` `aria-label="新建"`) with exactly three actions: `AI 拍照 / 截图录入衣物` (`/scan?mode=item`), `AI 街拍拆解找同款` (`/scan?mode=inspiration`), and `智能平铺搭配画板` (`/lookbook`). Calendar & Favorites are reached via `我的` → `穿搭与收藏` (the `/calendar` route is kept).

#### 2.3.1 AI 识别 (`/scan`, `VisionStudioView.vue`)
- Two segmented modes: `录入衣物` (`mode=item`) and `街拍找同款` (`mode=inspiration`, lazily loaded).
- **录入衣物 is a multi-item recognition list**: uploading a photo (or picking a built-in sample) starts recognition immediately — there is no separate "start" button. Left: the photo (with `原图 / 抠图` toggle on the single-item path). Right: a checklist where each row is `checkbox · garment thumbnail · category tag · name · 季节 + 风格标签 summary`; tapping a row expands an accordion for corrections (name, season chips, style tags, color, material, price). Footer: select-all and one black `添加到衣橱` button.
- **Real per-item images (multi-item path)**: with an active model the client calls `POST /api/vision/detect-items`; when ≥ 2 garments come back, each `bbox` is cropped on a canvas (`utils/crop.ts`), then cut out sequentially in the browser with a **dual-engine switcher (`自动 / 人像拆衣 / 单品抠图`)**: self-hosted `SegFormer-B2-Clothes` 18-class ONNX semantic segmentation (`/models/segformer_b2_clothes/model_quantized.onnx` + `/ort/` WASM via `utils/segformer.ts`, stripping face, hair, arms, legs, background, and adjacent garments) with graceful fallback to `@imgly/background-removal`. Every cutout can be manually refined via `CutoutMaskEditorModal.vue` (Erase/Restore brush, undo/redo, checkerboard/white/black backdrop) before saving or later from `ItemDetailDrawer.vue`. On save the crop (+ cutout) is uploaded with `POST /api/vision/store-item-image` and the wardrobe item stores the returned `/uploads/user_{id}/…` URLs. Items without a usable box, or whose upload fails, degrade to the `svg://{type}` illustration; the item is still saved. Never persist `data:` URLs to the wardrobe.
- 0–1 detected garments, or no API key, falls back to the single-item pipeline (`trySmartSingleCutout` + `POST /api/vision/recognize-item`).
- Pieces whose `similarWardrobeName` is set (an already-owned near-duplicate) are unchecked by default with an explanatory line.

### 2.4 Advanced Lookbook Studio, Visual Styling & Real-Photo OOTD Calendar (Implemented)
- **Infinite Spatial Flat-Lay Studio (`FlatLayStudioModal.vue` + `FlatLayCollageCanvas.vue` inside `/lookbook`)**:
  - Toggleable `网格平铺` / `自由拼贴` / `AI 上身预览` stage switcher; full-bleed infinite spatial canvas with cursor-anchored zoom, pan, unbounded world coordinates, magnetic snap guides, optional `16:9 / 4:5 / 1:1` viewfinder frames, transparent/opaque 2x PNG export, persistent layout save (`PUT /api/outfit/plans/{id}/collage-layout`), and **"AI 搭配体检"** (`POST /api/outfit/critique`, scoring Color / Proportion / Scene with one-click closet swap insertion).
- **VLM Visual Contact-Sheet Styling (`OutfitContactSheetService.java`)**:
  - When recommending outfits with an active API key, the backend stitches up to 16 top-scored candidate garments into a single `1024×1024` numbered JPEG Contact Sheet (`#1..#N`) using pure JDK `BufferedImage`/`Graphics2D` and passes it to `visionModel` (`curateOutfitPlanWithContactSheet`) so the AI inspects actual fabrics, colors, prints, and silhouettes (with automatic fallback to text `chatModel` → deterministic engine).
- **Real-Photo OOTD Calendar (`CalendarView.vue`)**:
  - Toggleable `实拍 OOTD 墙 / 色卡日程` view; clicking any empty date opens inline real-photo OOTD check-in (`POST /api/outfit/history/ootd-checkin`) with optional AI garment auto-matching (`POST /api/vision/detect-items`), wardrobe item tagging (updating `wearCount` & CPW), photo replace/delete (`POST/DELETE /api/outfit/history/{id}/photo`), and inline **"AI 体检这套实拍"** (`POST /api/outfit/critique`).
- **AI Modeled Editorial Try-On Preview (`TryOnPreviewPanel.vue` + `AiModelConfigCard.vue`)**:
  - Users can configure `imageModel` (default `gpt-image-1`) and upload a personal/model reference photo (`POST /api/ai-config/model-reference`, magic-byte validated).
  - `POST /api/outfit/plans/{id}/try-on` tries OpenAI-compatible `/v1/images/edits` (multipart with user reference photo + real uploaded garment cutouts) and falls back cleanly to `/v1/images/generations` JSON; when no image API key is configured or upstream fails, it degrades gracefully to a color/style-matched editorial studio preview while exposing the exact constructed prompt.

---

## 3. Backend & Security Engineering Invariants

### 3.1 Multi-Tenant Isolation & Auth
- Every `/api/**` endpoint except `/api/auth/login`, `/api/auth/register`, `/api/health`, and static `/uploads/**` MUST pass through `JwtAuthInterceptor`.
- Extract current `userId` exclusively from `UserContextHolder.getUserId()` (never trust client-supplied `userId`).
- Every DB query, update, and delete on `wardrobe_item`, `outfit_plan`, `outfit_history`, `user_preference`, `user_ai_config`, and `inspo_like` MUST enforce `user_id = currentUserId`. Cross-user IDOR attempts MUST return business code `40301` (HTTP `403`).
- Passwords MUST be hashed with `BCryptPasswordEncoder` (`strength >= 10`).

### 3.2 Dynamic Model API Key (Frontend-Editable, Server-Enforced)
- Users configure OpenAI-compatible credentials (`providerName`, `baseUrl`, `apiKey`, `visionModel`, `chatModel`, `enabled`) via `PUT /api/ai-config`.
- Encrypt `apiKey` at rest with AES-256-GCM (`AiKeyCryptoService`); `GET /api/ai-config` MUST only return `apiKeyMasked` and `hasApiKey: boolean`.
- All LLM/VLM HTTP calls run strictly on the backend (`MultimodalLlmClient`), preserving stored keys when `apiKey` is left blank and degrading gracefully to the local engine on missing key or timeout.

### 3.3 Unified Response & Error Codes (`Result<T>`)
All REST responses follow `{ "code": 200, "message": "success", "data": ..., "timestamp": ... }`:
- `40001` (HTTP 400): Parameter validation failure
- `40002` (HTTP 400): Invalid image format / magic-byte mismatch / size > 10MB
- `40003` (HTTP 400): Duplicate username on registration
- `40101` (HTTP 401): Missing, expired, or invalid JWT
- `40102` (HTTP 401): Wrong username or password
- `40301` (HTTP 403): Cross-user unauthorized access (IDOR attempt)
- `42201` (HTTP 422): Multimodal model classified image as non-clothing (`isClothing=false`)
- `50201` (HTTP 502): Upstream multimodal LLM timeout or unparseable output

### 3.4 Image Validation, CPW Metrics & Recommendation Formula
- Validate uploaded files by **magic bytes** (JPEG / PNG / WEBP) and size (`<= 10MB`) with UUID filenames under per-user upload folders.
- **CPW (Cost-Per-Wear)**: `wardrobe_item.price` is nullable `DECIMAL(10,2)`. Item CPW is `price / wearCount` (when `wearCount > 0`) or `price` (when unworn); `GET /api/preferences` computes `totalWardrobeValue`, `avgCostPerWear`, `pricedItemCount`, `bestValueItemName/Cpw`, and `highestCpwItemName/Cpw`.
- **Vision endpoints (`VisionController`, all behind `JwtAuthInterceptor`, userId from `UserContextHolder`)**:
  - `POST /api/vision/recognize-item` — single garment (original + optional browser cutout, LLM attributes or local filename fallback).
  - `POST /api/vision/detect-items` — multi-garment detection for wardrobe ingestion (`file`, `sourceType`). Response `ItemsDetectionVo`: `originalImageUrl`, `items[]` (≤ 8; name / category / seasons / styleTags / material / `svgType` / `bbox` / `similarWardrobeName`), `usedRealLlm`, `notice`. `bbox` is `{x, y, w, h}` as **fractions of the image (0..1, top-left origin)**; `VisionRecognitionService.normalizeBoundingBox` accepts fractions, 0–100 percentages, 0–1000 grids and corner boxes, clamps to the image, and returns `null` for unusable boxes (client then keeps an illustration for that piece). Without an active model key nothing is stored and `items` is `[]` (the file is still magic-byte validated). `isClothing=false` or an empty list → `42201`.
  - `POST /api/vision/store-item-image` — persists one client-side crop (`file`) and optional browser cutout (`cutoutFile`) into the caller's `/uploads/user_{id}/` folder; a broken cutout degrades to the crop (`cutoutFallback=true`), a broken crop is rejected with `40002`.
  - `POST /api/vision/analyze-inspiration` — street-snap breakdown with shopping matches (mode `街拍找同款`).
- **Personalized Recommendation Formula (PRD §4.3)**:
$$S(i) = 0.30 \cdot \text{TempMatch}(i, T) + 0.25 \cdot \text{StylePref}(i, P) + 0.15 \cdot \text{ColorPref}(i, P) + 0.30 \cdot \text{SceneMatch}(i, C) - 0.15 \cdot \text{RecentWearPenalty}(i, H)$$

---

## 4. Development & Verification Commands

### Backend (Spring Boot 3)
```powershell
cd backend
mvn spring-boot:run                                  # Local dev (H2 MySQL-mode file DB)
mvn spring-boot:run "-Dspring-boot.run.profiles=mysql"  # With Docker MySQL 8.0
mvn test                                             # Full integration & security test suite
```

### Frontend (Vue 3 + Vite + TS)
```powershell
cd frontend
pnpm install
pnpm dev        # http://localhost:5173 (proxies /api and /uploads to :8080)
pnpm build      # Type-check + production bundle verification
```

### Pre-Commit / Post-Optimization Checklist
1. Verify `mvn test` passes (JWT auth, `40301` IDOR isolation, `40002` magic-byte check, recommendation scoring, encrypted AI config, CPW calculation, China Inspo feed/like isolation, and `detect-items` / `store-item-image` auth, magic-byte, no-key and bbox-normalization cases).
2. Verify `pnpm build` completes cleanly with zero TypeScript or Vite bundling errors.
3. Visually verify in local Chrome (`chrome-devtools` MCP) that the Alta pure-white gallery canvas (`#FFFFFF`), centered floating Liquid Glass Dock + black `+` button, `/inspo` 3-layer flow (Grid/Top/Brands → Look Modal → Taobao/JD Piece Modal), and CPW metrics render cleanly with zero console errors or broken images.

---

## 5. Browser & Chrome DevTools MCP Workflow (Non-Isolated Local Chrome)

Whenever verifying UI/layout (`http://localhost:5173`) or inspecting live web targets:
1. Prefer globally configured `chrome-devtools` MCP (`--autoConnect` to local Chrome): start with `list_pages`, then `new_page` / `navigate_page` → `take_snapshot` / `take_screenshot` / `evaluate_script` / `list_console_messages`.
2. Only fall back to the built-in `browser` subagent when `chrome-devtools` MCP is unavailable.
