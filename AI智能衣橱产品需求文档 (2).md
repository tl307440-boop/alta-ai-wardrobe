# AI 智能衣橱 (AI Wardrobe) - Web MVP 产品需求与架构文档 (PRD)

> **文档说明**：本文档旨在为“AI 智能衣橱”网页版 MVP 提供清晰的产品边界、交互规范与技术架构指引。文档采用对 AI 编程助手（如 Cursor / Claude / Codex）友好的结构编写，开发时可直接将本文档作为全局上下文（Context）投喂给 AI，核心实现细节允许 AI 根据最佳实践进行自由发挥与魔改。

---

## 1. 项目概述 (Project Overview)

### 1.1 产品愿景
打造一款“懂你衣橱、懂你灵感”的私人 AI 穿搭顾问。从一张穿搭截图或一件实物衣服开始，帮助用户管理个人数字衣橱、自动生成符合天气与场合的全身搭配，并在缺失关键单品时提供高性价比的同款/平替网购导流，彻底解决“衣服多却不知道穿什么”以及“盲目买衣服不百搭”的痛点。

### 1.2 核心定位与平台
* **目标平台**：Web 端优先（采用响应式设计，主攻 PC/Mac 端大屏控制台与画布交互体验，同时兼容移动端浏览器访问）。
* **核心闭环**：`衣物/灵感录入` $\rightarrow$ `多模态解析入库` $\rightarrow$ `多维触发搭配（天气/指令/单品）` $\rightarrow$ `衣橱检索与缺品补全` $\rightarrow$ `相似商品导购外链`。

---

## 2. 核心功能需求 (Core Features - MVP)

### 2.1 录入与解析模块 (Data Ingestion)
解决传统衣橱软件“录入繁琐”的最大痛点，提供三种丝滑的入库方式：
1. **实物拍照录入**：
   * 用户上传自己拍摄的衣服实物照片。
   * **自动化处理**：后端自动调用图像分割/抠图工具去除杂乱背景，提取纯净的服装主体图；同时调用多模态大模型（Vision LLM）自动识别并填充属性标签（品类、颜色、材质、适用季节、风格）。
2. **电商购买截图录入**：
   * 用户上传淘宝、京东、拼多多等平台的订单截图或商品详情图。
   * **自动化处理**：多模态模型自动识别图中的服装主体与文字信息，提取单品图片及核心属性（如“深蓝直筒牛仔裤”、“米白针织开衫”），一键加入个人衣橱。
3. **穿搭灵感截图解析（核心亮点）**：
   * 用户上传小红书、抖音等平台的全身穿搭街拍图。
   * **自动化处理**：AI 自动框选并拆解图中的多个单品（外套、上衣、裤子、鞋子、包包），用户可勾选目标单品，系统自动在“个人衣橱”中检索是否有相似款，或直接发起全网同款搜索。

### 2.2 个人数字衣橱管理 (Digital Wardrobe)
* **可视化画廊**：以整洁的网格卡片形式展示所有已录入单品（展示抠图后的缩略图、名称、品类与颜色标签、风格标签）。
* **多维筛选与搜索**：支持按一级分类（全部、上衣、裤子、外套、鞋子、包包）及风格标签快速过滤，支持自然语言模糊搜索。
* **状态管理**：支持手动微调 AI 生成的标签、删除衣物，或直接点击某件衣物触发“以此单品为核心进行搭配”。

### 2.3 智能搭配引擎 (AI Styling Engine)
系统支持三种核心触发机制，由后端路由统一调度：
1. **客观环境驱动（今日穿搭）**：
   * 接入天气 API，获取用户所在地的实时天气与温度。
   * 结合温度与基础日常场景，自动从衣橱中召回合适厚度的单品组合（例如：降温时自动推荐“针织开衫 + 基础白 T + 牛仔裤”的叠穿方案）。
2. **自然语言指令驱动（场景/灵感定制）**：
   * 提供自然语言输入框与预算限制框（例如：“帮我找图中这件外套的相似款，预算 300 元以内，先看看我的衣橱里有没有”或“明天去参加互联网公司面试，帮我搭一套精神点但别太死板的衣服”）。
   * AI 解析场景、风格与预算约束，生成定制化穿搭方案。
3. **单品锚点驱动（一衣多搭）**：
   * 用户在衣橱中锁定 1~2 件必穿单品（如刚买的一双“棕色乐福鞋”）。
   * AI 围绕锚点单品的色彩与风格属性，从衣橱剩余衣物中补全上衣、下装与外套。

### 2.4 导购与外链模块 (Commerce Integration)
* **现有衣橱优先原则**：生成搭配方案时，优先使用用户【已有衣物】进行组合，并在界面上明确标注`（已拥有）`。
* **缺品补全与图文建议**：当现有衣橱无法完美匹配目标风格（如缺少一件“米色短款风衣”）时，AI 在穿搭方案中生成【需要购买】的推荐单品槽位，给出具体的文字购买建议与款式示意图。
* **商品库检索与外链挂载**：根据缺失单品的特征向量与用户的预算上限，在后端的“商品库”中进行相似度检索，返回匹配的候选商品卡片（包含商品图、名称、价格、平台标识如淘宝/京东/拼多多），支持点击跳转外部链接。

---

## 3. UI/UX 与页面结构规范 (UI & Interaction Design)

> **视觉基调参考**：采用温暖、极简、高雅的杂志/画廊风格。主背景色采用柔和的燕麦色/暖米白（如 `#F7F4EF`），卡片采用纯白或浅暖色圆角设计，主按钮采用高对比度的深炭黑（`#1E1E1E`），辅以温柔的陶土色/珊瑚色作为点缀。

### 3.1 全局布局
* **左侧固定导航栏 (Sidebar)**：品牌 Logo (`AI Wardrobe`)、核心路由切换（`首页`、`AI 识别`、`我的衣橱`、`推荐结果`），底部展示当前版本号与简短 Slogan。
* **右侧主工作区 (Main Workspace)**：顶部显示当前日期、问候语标题与用户头像；下方为具体页面的响应式网格内容区。

### 3.2 核心页面拆解
1. **首页 (Dashboard)**：
   * 顶部大横幅 Hero Card：“把灵感，穿进你的衣橱”，带有一键跳转【开始分析】的 CTA 按钮。
   * 下方 Quick Start 快捷入口三宫格：`导入穿搭截图`、`管理我的衣橱（显示已有衣物数量）`、`查看最近推荐（显示上次分析记录）`。
2. **AI 识别 / 灵感导入页 (AI Analysis)**：
   * 左栏：大尺寸拖拽/点击上传图片区域（支持 JPG/PNG）。
   * 右栏：多行自然语言输入框（“告诉 AI 你想做什么”）、预算上限数字输入框（元）、以及【开始识别 $\rightarrow$】主按钮。
3. **我的衣橱页 (My Wardrobe)**：
   * 顶部操作栏：显示衣物总数，右侧提供【+ 添加衣物】按钮（触发拍照/购买截图上传弹窗）。
   * 衣物卡片网格：每张卡片包含居中展示的衣物缩略图、粗体名称（如“米白针织开衫”）、副标题属性（“外套 · 米白”）以及底部的风格胶囊标签（“温柔通勤”）。
4. **推荐结果 / 今日穿搭页 (Curated Look)**：
   * 左栏（核心方案卡）：展示 AI 推荐理由标题（如“温柔通勤 · 轻松出门”）、具体的搭配逻辑说明，以及排成一排的全身单品缩略图组合。
   * 右栏（分栏详情）：
     * 上半部分【衣橱里可以这样搭】：列出本次方案中调用的已有衣物明细。
     * 下半部分【预算内的相似商品】：当存在缺失单品或同款搜索需求时，以列表/卡片形式展示推荐购买的商品及价格、外链入口。

---

## 4. 技术架构与 LangGraph 工作流 (Technical Architecture)

### 4.1 推荐技术栈 (Tech Stack)
* **前端 (Frontend)**：Next.js (App Router) + React + TypeScript + Tailwind CSS + Shadcn UI + Lucide Icons（状态管理可用 Zustand）。
* **后端 (Backend)**：Python FastAPI（提供异步 RESTful API 与 SSE 流式响应）。
* **AI 编排框架**：LangGraph + LangChain（核心多 Agent 协同与状态机管理）。
* **大模型接入**：支持 OpenAI (`gpt-4o` / `gpt-4o-mini`)、Claude 3.5 Sonnet 或国内兼容 OpenAI 接口的多模态大模型（如 Qwen-VL、GLM-4V 等）。
* **数据库与存储**：
  * **关系型数据库**：PostgreSQL 或 MySQL（使用 SQLAlchemy / SQLModel ORM，存储用户、衣物结构化元数据、穿搭历史记录）。
  * **向量数据库**：ChromaDB（本地持久化或独立容器，用于存储衣物特征 Embedding 和模拟商品库 Embedding，支持 CLIP 跨模态检索或文本 Embedding 检索）。
  * **缓存与会话状态**：Redis（用于 LangGraph Checkpointer 会话状态持久化，支持多轮对话微调穿搭，如“把裤子换成黑色的”）。
* **图像处理工具**：可集成 `rembg`（本地开源去背景库）或第三方视觉抠图 API。

### 4.2 LangGraph 核心工作流设计 (Core Graph Design)

系统的核心智能调度由一个状态图（StateGraph）驱动。以下为基础骨架设计，**AI 编程时可根据实际工具链自由优化节点拆分**：

#### 1. 全局图状态定义 (`WardrobeState`)
```python
from typing import TypedDict, List, Optional, Literal, Dict, Any

class WardrobeState(TypedDict):
    user_id: str
    session_id: str
    # 输入层
    user_query: Optional[str]          # 用户的自然语言指令
    uploaded_image_url: Optional[str]  # 上传的穿搭截图或实物图路径
    budget_limit: Optional[float]      # 预算上限
    trigger_type: Literal["ingest_item", "inspiration_search", "daily_outfit", "chat_refine"]
    
    # 上下文与环境层
    weather_info: Optional[Dict[str, Any]]  # 天气与温度数据
    parsed_items: List[Dict[str, Any]]      # 视觉节点解析出的单品列表及属性
    
    # 检索与推理层
    matched_wardrobe_items: List[Dict[str, Any]] # 从个人衣橱召回的已有单品
    missing_item_descriptions: List[str]         # 造型师判定缺失的单品描述
    recommended_products: List[Dict[str, Any]]   # 从商品库检索到的外链商品
    
    # 输出层
    final_outfit_plan: Dict[str, Any]  # 最终生成的结构化穿搭方案（含推荐理由、单品组合等）
```

#### 2. 核心节点 (Nodes) 与流转逻辑
1. **`router_node` (意图路由节点)**：
   * 分析用户的输入组合（是否有图、文本意图是什么），决定走向【衣物录入流】、【灵感拆解同款流】还是【日常搭配推荐流】。
2. **`vision_ingestion_node` (多模态视觉解析节点)**：
   * **录入模式**：执行去背景抠图 $\rightarrow$ 提取单品语义标签（颜色、品类、季节、风格描述） $\rightarrow$ 写入 MySQL 并生成 Embedding 存入 ChromaDB（`user_wardrobe` 集合）。
   * **灵感拆解模式**：识别穿搭图中的全套单品列表，提取每个单品的视觉特征描述，传递给下游检索节点。
3. **`context_enrichment_node` (环境感知节点)**：
   * 若触发的是“今日穿搭”或涉及出行场景，自动调用天气 API（或 Mock 天气服务）补充当前温湿度与穿衣指数。
4. **`wardrobe_rag_node` (个人衣橱检索节点)**：
   * 根据目标风格、天气要求或灵感图拆解出的单品特征，在 ChromaDB 的用户专属 Namespace 下进行向量相似度检索 + 元数据过滤（如过滤品类 `category="外套"`），召回用户已经拥有的相似或百搭单品。
5. **`stylist_agent_node` (主造型师推理节点)**：
   * 汇总用户指令、天气、以及召回的已有衣物。
   * **核心逻辑**：评估现有衣物能否拼凑出完美方案。如果可以，直接生成搭配组合与推荐文案；如果发现缺少关键单品（或者用户明确要求找外网同款），则生成缺失单品的精准检索 Query，触发下游导购节点。
6. **`commerce_search_node` (相似商品检索节点)**：
   * 接收缺失单品特征与用户的 `budget_limit`（如 `< 300元`），在 ChromaDB 的 `mock_products`（商品测试库）中进行向量检索与价格过滤，返回带购买链接的商品列表，最终合并输出至前端。

---

## 5. 数据库与向量库模型参考 (Data Models)

> 以下为最小化核心表结构参考，开发时可根据需要自由扩充字段。

### 5.1 关系型数据库 (MySQL / PostgreSQL)

**1. `wardrobe_items` (个人衣橱单品表)**
* `id` (UUID/Int, PK)
* `user_id` (String, Index)
* `name` (String) - 例："米白针织开衫"
* `category` (Enum/String) - 例：`top` (上衣), `bottom` (裤子/裙子), `outerwear` (外套), `shoes` (鞋子), `accessory` (包配)
* `color` (String) - 例："米白"
* `style_tags` (JSON/Array) - 例：`["温柔通勤", "简约", "春秋季"]`
* `image_url` (String) - 抠图后的透明背景图或原图 URL
* `source_type` (String) - `photo` (实物拍摄) / `screenshot` (订单截图)
* `created_at` (Timestamp)

**2. `mock_products` (模拟电商商品库 - 用于 MVP 演示导购闭环)**
* `id` (UUID/Int, PK)
* `title` (String) - 商品标题
* `category` (String) - 品类
* `price` (Float) - 价格（用于预算过滤）
* `platform` (String) - `taobao` / `jd` / `pdd`
* `image_url` (String) - 商品主图
* `product_url` (String) - 外部跳转链接
* `style_description` (Text) - 用于生成检索向量的详细描述

**3. `outfit_records` (穿搭方案生成记录表)**
* `id` (UUID/Int, PK)
* `user_id` (String)
* `theme_title` (String) - 例："温柔通勤 · 轻松出门"
* `ai_reasoning` (Text) - AI 推荐理由文案
* `existing_item_ids` (JSON) - 本次搭配使用的已有衣橱单品 ID 列表
* `recommended_product_ids` (JSON) - 推荐购买的补充商品 ID 列表
* `created_at` (Timestamp)

---

## 6. AI 辅助编程推进指南 (AI Coding Roadmap)

在将本文档交给 AI 编程工具进行开发时，建议按照以下四个阶段（Phases）分步下达指令，以确保代码生成质量：

* **Phase 1：基础脚手架与高颜值 UI 还原**
  * 初始化 Next.js 前端与 FastAPI 后端目录结构。
  * 依据【第 3 节 UI 规范】与视觉参考图，先用 Mock 数据完整还原四个核心页面（首页、AI 识别页、我的衣橱页、推荐结果页）的静态组件与路由跳转，确保“燕麦色极简画廊风”视觉到位。
* **Phase 2：数据库初始化与模拟数据注入 (Seed Data)**
  * 搭建 SQLite/PostgreSQL 与本地 ChromaDB。
  * 编写一个 `seed.py` 脚本：自动预置 10~15 件常见基础款个人衣物（含示例图片与标签）以及 20~30 件不同价位的模拟电商商品，并完成向量化入库。这样无需手动拍照即可立即测试后续的推荐闭环。
* **Phase 3：打通 LangGraph 智能搭配与录入工作流**
  * 实现 `WardrobeState` 及各个核心 Agent 节点。
  * 接入多模态大模型 API，测试“上传截图 $\rightarrow$ 自动提取属性入库”以及“输入预算和需求 $\rightarrow$ 混合召回已有衣物与外部商品”的完整链路。
* **Phase 4：前后端联调与体验打磨**
  * 对接前端上传组件与后端的异步分析接口，增加优雅的 Loading 骨架屏与状态提示（如：“正在扫描您的衣橱...”、“正在对比全网相似款式...”）。