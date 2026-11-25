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
- **No Left Sidebar**: Keep the centered full-canvas gallery layout (`max-w-[920px]` on `/inspo`, `max-w-[1080px]` on workspace views) with generous bottom breathing room (`pb-28`) for the floating dock.
- **Minimal Sticky Top Header (`h-14`)**: `ALTA · 智搭` wordmark + route title on left; lightweight desktop pill shortcuts (`搭配画板` / `AI 识图` / `穿搭日历`) in center; interactive Weather Capsule + Logout button on right.
- **Unified Floating Liquid Glass Bottom Dock (`#alta-floating-dock`)**:
  - Centered `max-w-[540px]` pill with sliding translucent highlight capsule (`25%` width per tab):
    1. **首页 (`/`)** — Minimal Hero + Today's Curated Look + Top 6 China Inspo preview + Indyx CPW asset card + recent favorites.
    2. **衣橱 (`/wardrobe`)** — CPW asset header strip + category/color/keyword filters + **CPW sort selector** (`CPW 最低` / `CPW 最高` / `穿着最多`) + `4:5` gallery cards with CPW badges + `ItemDetailDrawer` (editable `price`, live CPW insight, reverse calendar lookup).
    3. **灵感发现 🔍 (`/inspo`)** — China-adapted Alta Inspo experience:
       - Top pill search bar + liked-only heart filter (`POST /api/inspo/looks/{id}/like`).
       - Three underline tabs: `为你推荐` (dual editorial banners + 6-col borderless model wall with China cities), `热度榜` (`女装 / 男装` pill toggle + `.alta-rank-stroke` outlined rank numbers), `品牌精选` (China designer & urban brands with Taobao/JD flagship links).
       - **Layer 2 (`InspoLookModal.vue`)**: Full-height white showcase with top-left `<`, top-right `...`, **left floating vertical piece rail** (click any clothing/jewelry piece), centered full-body model photo, bottom-right vertical social actions (like, comment drawer, one-click restyle into `/lookbook`).
       - **Layer 3 (`InspoPieceModal.vue`)**: `来自 XX 的衣橱` piece hero + direct **Taobao (`s.taobao.com/search`) & JD (`search.jd.com/Search`)** links + `收入我的衣橱` (records `price` for CPW) + 4-column `选购相似同款` grid with Taobao/JD filtering.
    4. **我的 (`/profile`)** — Style/color/city/budget preferences, Server AI Model & API Key config card (`AiModelConfigCard.vue`), Indyx CPW deep asset summary, category donut, style affinity, and wardrobe palette.
  - **Independent `56×56` Black Circular `+` Button**: Opens a floating Action Sheet linking to AI Item Scan (`/scan?mode=item`), AI Street-Snap Breakdown (`/scan?mode=inspiration`), Smart Lookbook Canvas (`/lookbook`), and Calendar & Favorites (`/calendar`).

### 2.4 Advanced Lookbook Studio Capabilities (Implemented)
- **Free-Form Flat-Lay Collage Canvas (`FlatLayCollageCanvas.vue` inside `/lookbook`)**:
  - Toggleable `网格平铺` / `自由拼贴` / `AI 上身预览` stage switcher.
  - Supports Pointer-Event drag `(x, y)`, scale (`0.55x–1.70x`), rotation (`-30°..+30°`), layer ordering (`zIndex` bring-to-front / send-to-back / step up / step down for tuck-in & layering), persistent layout save (`PUT /api/outfit/plans/{id}/collage-layout`), auto-layout reset, and native HTML5 Canvas export to `1080×1350` editorial PNG.
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
1. Verify `mvn test` passes (JWT auth, `40301` IDOR isolation, `40002` magic-byte check, recommendation scoring, encrypted AI config, CPW calculation, and China Inspo feed/like isolation).
2. Verify `pnpm build` completes cleanly with zero TypeScript or Vite bundling errors.
3. Visually verify in local Chrome (`chrome-devtools` MCP) that the Alta pure-white gallery canvas (`#FFFFFF`), centered floating Liquid Glass Dock + black `+` button, `/inspo` 3-layer flow (Grid/Top/Brands → Look Modal → Taobao/JD Piece Modal), and CPW metrics render cleanly with zero console errors or broken images.

---

## 5. Browser & Chrome DevTools MCP Workflow (Non-Isolated Local Chrome)

Whenever verifying UI/layout (`http://localhost:5173`) or inspecting live web targets:
1. Prefer globally configured `chrome-devtools` MCP (`--autoConnect` to local Chrome): start with `list_pages`, then `new_page` / `navigate_page` → `take_snapshot` / `take_screenshot` / `evaluate_script` / `list_console_messages`.
2. Only fall back to the built-in `browser` subagent when `chrome-devtools` MCP is unavailable.
