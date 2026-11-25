# ALTA · 智搭衣橱 (AI Wardrobe)

基于多模态大模型与个性化推荐的智能衣橱系统（**Spring Boot 3 + Vue 3 + TypeScript + Tailwind CSS + MySQL 8 + Docker CI/CD**），采用 **Alta Monochrome Editorial Gallery（极简纯白画廊 + 液态毛玻璃悬浮 Dock + 中国本土灵感导购闭环）** 视觉体系。

## 核心特性

- **多用户 JWT 鉴权与数据隔离**：BCrypt 密码加密、7 天 JWT 登录态、内置演示账号 `demo_user` / `Wardrobe2026!` 一键登录；后端严格按 `userId` 隔离所有业务操作并拦截水平越权 (`40301`)。
- **模型 API Key 前端可配、服务端实时生效**：支持填写任意 OpenAI 兼容端点（通义千问 DashScope / 智谱 GLM-4V / OpenAI / DeepSeek / Gemini 兼容协议），后端使用 **AES-256-GCM** 加密落库、前端仅脱敏回显；未配置时自动降级至本地智能引擎。
- **5 步引导衣物录入 & 浏览器本地 AI 抠图**：上传图片自动进行文件魔数校验 (`40002`)，前端使用 `@imgly/background-removal` 本地抠图，支持多模态属性解析与胶囊人工修正入库。
- **Alta 灵感发现广场 & 淘宝/京东同款导购**：内置高清透明底棚拍模特与服饰单品切片（`/alta/*`），支持三层灵感交互（推荐/热度榜/品牌精选 → 全身单品拆解 → 淘宝/京东同款比价与一键收入衣橱）。
- **深度个性化推荐 & 自由拼贴 / AI 杂志上身预览**：融合实时天气、个人身形/四季色彩档案、Style Mirror 穿搭指纹、唤醒闲置单品模式与负反馈降权；支持自由平铺拼贴导出及服务端持久化的异步 AI 试穿预览（刷新不丢失）。
- **Indyx CPW 单次穿着成本资产分析**：联动穿搭日历自动统计衣橱总资产、平均 CPW、性价比之王与待唤醒单品。

## 本地开发快速启动

### 1. 启动后端（两种数据库模式任选）

**方式 A：零依赖本地开发模式（默认 H2 MySQL 兼容文件库，自动初始化演示数据）**
```powershell
cd backend
mvn spring-boot:run
```

**方式 B：Docker MySQL 8.0 模式**
```powershell
docker compose up -d
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=mysql"
```

### 2. 启动前端

```powershell
cd frontend
pnpm install
pnpm dev
```

访问 `http://localhost:5173` 即可体验完整功能。

## Docker 生产部署与 GitHub Actions CI/CD

项目专为低配云服务器（如 2核2G ECS）优化，**所有编译与镜像构建均在本地开发机或 GitHub Actions 构建机完成**，不在目标服务器上执行 `docker build`：

- **生产编排**：[`docker-compose.prod.yml`](./docker-compose.prod.yml)（低内存调优版 `mysql:8.0` + `eclipse-temurin:21-jre-alpine` SerialGC 后端 + `nginx:1.27-alpine` 前端反向代理）
- **本地一键构建推送**：
  ```powershell
  powershell -ExecutionPolicy Bypass -File .\scripts\deploy-aliyun.ps1
  ```
- **GitHub Actions 自动部署**：[`deploy.yml`](./.github/workflows/deploy.yml)
  - PR 阶段自动触发 [`ci.yml`](./.github/workflows/ci.yml) 执行后端安全/集成测试与前端类型构建检查；
  - 合并 PR 或推送到 `main` 分支后自动触发构建并将压缩镜像流式推送至阿里云 ECS 热更新容器栈（需在 GitHub Secrets 配置 `ECS_HOST` 与 `ECS_SSH_KEY`，服务器环境变量存于 `/opt/alta_wardrobe/.env`）。

## 运行测试与构建验证

```powershell
# 后端全链路集成测试
cd backend
mvn test

# 前端 TypeScript 类型检查与生产构建
cd frontend
pnpm build
```

## 开发与视觉规范

详见根目录 [AGENTS.md](./AGENTS.md)。
