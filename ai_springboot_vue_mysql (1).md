# 基于多模态大模型与个性化推荐的智能衣橱系统 - 完整需求与架构设计文档 (PRD)

> **文档说明**：本文档严格对齐毕业设计任务书《基于多模态大模型与个性化推荐的智能衣橱系统设计与实现》的核心要求，将现代 AI 智能衣橱的产品体验、多用户鉴权闭环与高颜值 UI 设计完整落地至 **B/S 架构（SpringBoot 3 + Vue 3 + MySQL 8）**。文档采用对 AI 编程助手（Cursor / Claude / Trae）高度友好的全量上下文结构，涵盖业务需求、UI 规范、JWT 安全架构、推荐算法、数据库建表与初始化数据、API 接口契约、Prompt 工程及论文测试方案，可直接按阶段用于全栈编码与毕业论文撰写。

---

## 1. 项目概述 (Project Overview)

### 1.1 课题背景与产品愿景
针对传统衣物整理方式繁琐、穿搭搭配依赖个人经验、难以结合实时天气与出行场景快速选衣，以及网购盲目消费导致单品不百搭等痛点，本系统依托**多模态大模型的图像语义理解能力**与**个性化加权推荐策略**，构建集数字衣橱管理、智能抠图解析、场景化穿搭推荐、穿搭日历沉淀与预算内缺品导购于一体的 Web 智能穿搭平台。

### 1.2 核心定位与完整业务闭环
* **目标平台**：Web 端 B/S 架构（响应式设计，兼顾 PC 端大屏控制台画布与移动端浏览器 PWA 级体验）。
* **多用户隔离**：基于 JWT 无状态鉴权，每位注册用户拥有完全独立的数字衣橱、穿搭偏好、方案收藏与历史日历数据。
* **完整业务闭环**：
  `注册/JWT 登录鉴权` $\rightarrow$ `实物照/订单截图/街拍灵感图上传` $\rightarrow$ `图片校验与智能抠图预处理` $\rightarrow$ `多模态大模型结构化解析` $\rightarrow$ `人工复核纠偏入库` $\rightarrow$ `多维触发个性化推荐（天气/场景/自然语言/单品锚点）` $\rightarrow$ `平铺画板局部换装与微调` $\rightarrow$ `方案收藏、日历留存与满意度反馈` $\rightarrow$ `缺品补全与预算内商品导购`。

---

## 2. 核心功能需求 (Core Features)

### 2.1 用户认证与多用户数据隔离 (Authentication & Multi-Tenancy)
严格满足任务书中“用户表”及关联业务的数据安全与隔离要求：
1. **用户注册 (`/api/auth/register`)**：
   * 支持输入用户名、用户昵称、密码、确认密码及默认常驻城市。
   * 后端校验用户名唯一性、密码强度（至少 6 位），采用 `BCrypt` 算法加盐哈希加密后写入 `sys_user`，同时自动在 `user_preference` 表中初始化一条默认偏好记录。
   * **新手引导选项**：注册时可选“导入 3 件基础款新手衣物”或“从纯空白衣橱开始”，便于快速上手与答辩现场演示多用户数据隔离。
2. **用户登录 (`/api/auth/login`)**：
   * 支持用户名 + 密码校验登录，校验通过后后端签发带过期时间（如 7 天）的 **JWT Token**，返回基础用户信息与 Token。
   * **答辩快捷演示机制**：登录卡片底部提供「一键登录演示账号」入口（预置账号 `demo_user`，含 11 件基础款衣橱单品、4 条穿搭日历与 3 套收藏方案），兼顾正规鉴权演示与高效答辩体验。
3. **会话保持、退出与越权防护**：
   * 前端将 JWT 持久化至 `localStorage`，并通过路由守卫（Vue Router Guard）拦截未登录访问，自动跳转至登录页。
   * 后端统一通过 JWT 拦截器解析 `userId` 并绑定至当前请求上下文，**所有查询、修改、删除操作强制拼接 `WHERE user_id = #{currentUserId}`**，彻底杜绝水平越权。
   * 支持随时点击侧边栏或顶部账号卡片执行「退出登录 / 切换账号」，清除本地 Token 与 Pinia 内存状态。

### 2.2 个人偏好与动态风格画像 (User Preference & Style Profile)
* **显性偏好配置**：用户可配置常驻城市（自动拉取实时天气）、喜爱风格（多选：极简通勤、温柔韩系、美拉德复古、Clean Fit、学院风、法式松弛）、偏好色系、避雷色系（推荐时自动降权）及默认单品购买预算上限。
* **隐性偏好演进**：系统结合用户衣橱单品分布、方案收藏记录与穿搭历史满意度打分，动态更新 `user_preference.style_weights`，并在前端以可视化图表展示实时品类分布、主色盘与隐性风格倾向画像。

### 2.3 多模态衣物录入、智能抠图与人工修正 (Vision Ingestion)
1. **实物拍照录入（含自动抠图）**：
   * 上传手机拍摄的衣物实拍图，后端完成格式/体积校验后调用图像分割 API 去除杂乱背景，生成透明底平铺图；再调用多模态大模型提取 **名称、一级品类、细分款式、主色调、适用季节、厚薄等级、风格标签与材质**。
2. **电商订单/商品截图录入**：
   * 上传淘宝、京东、拼多多等平台的商品详情或订单截图，多模态模型同步理解图像主体与页面文字描述，一键提取结构化单品信息。
3. **5 步扫描复核与人工修正（任务书核心要求）**：
   * 录入流程可视化展示 5 个阶段：`1. 图片校验` $\rightarrow$ `2. 智能抠图` $\rightarrow$ `3. 特征提取` $\rightarrow$ `4. 属性结构化` $\rightarrow$ `5. 人工复核`。
   * 解析完成后支持**原图与纯净抠图一键切换对比**，用户可通过点击品类胶囊、可视化色盘与季节标签直接对 AI 误识别字段进行**人工修正**；若发生人工修改，入库时记录 `is_manually_corrected = 1` 并保留原始 `ai_raw_result` 以便论文做识别准确率分析。
   * 同时支持纯手动录入衣物表单。
4. **穿搭灵感街拍拆解（核心亮点）**：
   * 用户上传小红书、抖音等平台的全身穿搭街拍图，填写自然语言需求与单品预算上限。
   * 多模态模型自动框选拆解图中的全套单品（外套、上衣、下装、鞋履、包配），依次在**当前登录用户的个人衣橱**中比对相似已有款（标注相似度与复用建议），对衣橱缺失单品按预算上限从模拟商品库召回同款/平替。

### 2.4 数字衣橱管理模块 (Digital Wardrobe)
* **画廊式展示**：`4:5` 比例卡片展示抠图平铺单品、名称、品类、色点、风格胶囊及累计穿着次数徽章。
* **多维检索与过滤**：支持一级品类筛选、可视化色系圆点条（Color Swatch）筛选、季节筛选以及关键词模糊搜索（匹配名称、颜色、风格标签）。
* **侧边详情抽屉与完整 CRUD**：点击卡片从右侧滑出详情抽屉，支持编辑属性、添加/删除风格标签、带二次确认的删除衣物操作；抽屉底部支持**反向关联查询**（展示该衣物参与过的历史穿搭日历记录），并提供一键【以此单品为核心搭配】入口。

### 2.5 个性化穿搭推荐引擎 (Personalized Styling Engine)
支持四种推荐触发方式与局部交互优化：
1. **实时天气与场景驱动（今日穿搭）**：调用天气 API 获取用户常驻城市实时温湿度与天气状况，结合出行场景（职场通勤、周末约会、互联网面试、日常休闲、旅行出游）生成适温全身搭配。
2. **自然语言指令驱动（场景定制）**：用户输入口语化需求（如“明天去互联网公司面试，想要精神但别太死板的搭配”）与预算约束，AI 解析语义并输出定制方案。
3. **单品锚点驱动（一衣多搭）**：锁定衣橱中任意 1 件核心单品（如焦糖棕乐福鞋），围绕其风格与色系从其余衣物中自动补全全身组合。
4. **局部换一件与多轮微调**：在生成平铺画板后，支持对任意非锚点槽位点击【局部换一件】循环切换同品类已有衣物，也支持在底部对话栏输入微调指令（如“把裤子换成黑色阔腿裤”、“预算降到 200 元以内”、“换一双更休闲的鞋”）。

### 2.6 穿搭方案、收藏、日历历史与缺品导购 (Lookbook, Calendar & Commerce)
* **现有衣橱优先原则**：搭配方案优先调用当前用户的已有衣物并标注`衣橱已有`，附带 AI 造型师对色彩呼应、温度层次与场景契合度的文字解析。
* **方案保存与收藏（任务书核心要求）**：支持一键收藏/取消收藏当前穿搭方案，在“收藏馆”中按场景浏览与一键重新载入画板。
* **天气穿搭日历与反馈闭环**：点击【记录为今日穿搭】将方案写入 `outfit_history`（记录日期、天气、温度、场景、实际穿着单品 ID），并自动触发关联衣物的 `wear_count + 1` 与 `last_worn_date` 更新；在月视图日历中支持按日期回溯当日穿搭、填写个人心得笔记与 $1\text{--}5$ 星满意度打分。
* **缺品补全与模拟商品导购**：当衣橱缺少关键风格单品时，方案中生成`建议购入`虚线槽位，并依据缺失品类、风格与用户预算上限（`price <= budget_limit`）从 `mock_product` 表召回淘宝/京东/拼多多模拟商品卡片，支持预览外链及“模拟购买并一键加入衣橱”。

### 2.7 非功能需求与统一异常处理规范 (Non-Functional & Exception Handling)
后端通过 `@RestControllerAdvice` 实现全局异常拦截与降级兜底，满足任务书健壮性要求：
* **图片校验拦截**：严格校验 MIME 类型与文件魔数（仅允许 `JPG/JPEG/PNG/WEBP`）及文件大小（$\le 10\text{MB}$），拦截伪装后缀文件与损坏文件。
* **外部接口超时与优雅降级**：
  * 多模态视觉模型设置连接超时 $5\text{s}$、读取超时 $30\text{s}$，失败重试 1 次；超时返回友好提示码 `50201`。
  * 图像抠图 API 设置超时 $8\text{s}$；若超时或欠费，自动降级保存压缩后的原图并在响应中标记 `cutoutFallback = true`，不阻断入库主流程。
  * 天气 API 设置超时 $5\text{s}$ 并按城市缓存 30 分钟；若不可用自动降级为默认温和天气或允许前端手动切换温度场景。
* **AI 输出格式异常兜底**：后端对大模型返回文本执行正则清洗（剥离多余解释文字与 Markdown 代码围栏）及 JSON Schema 校验；若检测到 `isClothing = false`（如用户上传风景/宠物图）则明确提示；若 JSON 残缺则触发一次自动修复重试，仍失败则返回空白人工编辑模板。

---

## 3. UI/UX 与页面结构规范（借鉴 GitHub 优秀开源项目）

### 3.1 开源项目设计借鉴对照表

| 参考开源项目 (GitHub) | 核心借鉴亮点 | 在本系统中的落地应用 |
| :--- | :--- | :--- |
| **`unovue/shadcn-vue` & `unovue/inspira-ui`** | 无锁定样式 Token、高雅极简卡片、侧边抽屉（Sheet）与 AI 扫描光效微动效 | 替代呆板后台模板，构建暖燕麦杂志画廊视觉体系与扫描加载动效 |
| **`dariadraghici/PICKyourFIT`** | 衬线体杂志排版、**登录 + 游客/演示快捷体验**、5 步扫描复核流与平铺拼贴画板 | 落地“演示账号一键登录”、5 步抠图纠偏工作台及 Flat-Lay 平铺穿搭画板 |
| **`Lazztech/Libre-Closet` & `Iyosiprograming/closet`** | 多用户 JWT 账号体系、桌面侧边栏 + 移动底部栏、**带天气徽章的穿搭日历**与单品反查 | 落地多用户隔离登录态、月视图天气穿搭日历及单品详情关联历史反查 |
| **`Ligoml/ClosetAI`** | 透明底平铺图（Flat-lay）、**胶囊单多选零负担打标**、可视化色盘筛选（Color Swatch） | 消除长表单填写负担，用点击色点与胶囊标签完成筛选与人工纠偏 |

### 3.2 视觉设计系统 (Design Tokens)
* **核心色彩**：
  * 画布主背景（Oat Canvas）：`#F7F4EF`
  * 浅米展台底色（Oat Surface）：`#EFECE6` / `#FAF8F5`
  * 卡片与浮层（Pure White）：`#FFFFFF`，边框 `1px solid #E8E2D8`
  * 主操作色（Charcoal）：`#1E1E1E`（悬浮 `#333333`）
  * 强调与情感色（Terracotta 陶土色）：`#C86D51`（浅底 `#F6E8E3`、深字 `#A8553B`）
  * 次级说明文字（Warm Gray）：`#78716C`
* **字体排版体系**：
  * **杂志标题 / Look 主题**：`Fraunces` + `Noto Serif SC`
  * **界面正文 / 表单**：`Inter` + `Noto Sans SC`
  * **数据 / 温度 / 价格 / 匹配度**：`JetBrains Mono`
* **圆角与投影**：卡片统一采用 `rounded-3xl`（`24px`）与 `rounded-2xl`（`16px`），搭配柔和暖色调投影，保持画廊留白呼吸感。

### 3.3 全局布局与 7 大核心页面规范
* **未登录态**：全屏展示 **Page 0 左右分栏杂志风登录/注册页**，无后台侧边栏干扰。
* **已登录态**：
  * **桌面端（$\ge 1024\text{px}$）**：左侧固定 `240px` 侧边栏（Logo、6 大功能路由、底部当前账号头像卡片与退出按钮）+ 右侧沉浸式画布与吸顶毛玻璃顶栏（日期问候、可切换的实时天气胶囊、`+ 录入衣物` 快捷按钮）。
  * **移动端（$< 768\text{px}$）**：隐藏左侧边栏，切换为底部悬浮导航栏 + 顶栏快捷退出/切换账号按钮。

#### 核心页面清单
0. **Page 0：登录与注册页 (Editorial Split Auth Page)**
   * **左栏（54%）**：燕麦波点平铺画布，呈现品牌 Slogan“把灵感，穿进你的衣橱”、三张悬浮斜角穿搭平铺单品卡及系统核心特性标签。
   * **右栏（46%）**：纯白大圆角认证卡，顶部 Segmented 切换「账号登录 / 注册新账号」。登录页支持密码显隐、表单校验错误提示、7 天记住状态及高亮的**「演示账号一键登录（林小满）」**卡片；注册页支持常驻城市选择与“是否导入新手基础款衣物”勾选。
1. **Page 1：首页概览 (Bento Grid Dashboard)**
   * 采用便当盒网格：左上方为杂志风主横幅与拼贴图，右上方为“今日天气推荐 Look”微缩画板；中左为数字衣橱资产统计卡（收录单品数、本月穿搭数、低频闲置率、品类进度条），中右为 3 个快捷入口卡；底部为近期收藏方案横向展架。
2. **Page 2：AI 识别与灵感工作台 (AI Vision Studio)**
   * 顶部切换 **模式 A（单品入库 & 智能抠图）** 与 **模式 B（街拍拆解 & 找同款）**。
   * 模式 A 含 5 步进度条、带激光扫描动画的上传区、原图/棋盘格抠图对比切换，以及右侧胶囊/色盘人工纠偏表单。
   * 模式 B 含带脉动定位热点的街拍展示画布、自然语言+预算输入框，以及右侧逐件单品的“衣橱已有相似度匹配 / 预算内平替商品卡”联动面板。
3. **Page 3：我的数字衣橱 (Digital Closet Gallery)**
   * 顶部分层轻筛选（品类胶囊 + 模糊搜索 + 色系圆点条），下方为 `4:5` 透明平铺画廊卡片（角标显示穿着次数，悬浮滑出【以此单品为核心搭配】）；点击卡片滑出右侧详情抽屉，支持属性编辑、历史穿搭反查与删除。
4. **Page 4：智能搭配画板 (Curated Lookbook Studio)**
   * 顶部指挥条支持在「今日天气驱动」「自然语言场景定制」「单品锚点一衣多搭」间无缝切换，并实时调整缺品导购预算。
   * 左栏为 **Editorial Flat-Lay 平铺拼贴画板**，单品支持右上角【🔄 局部换一件】，底部提供收藏、记录为今日穿搭及多轮微调对话条；右栏分上下两区展示【衣橱里可以这样搭】与【预算内缺品导购商品】。
5. **Page 5：穿搭日历与收藏馆 (Outfit Calendar & Favorites)**
   * Tab 1 为月视图穿搭日历，已记录日期展示当日温度、天气图标与配色色点，右侧联动展示当日单品组合、AI 搭配理由、星级打分与心得笔记编辑。
   * Tab 2 为网格化 Lookbook 收藏馆，支持一键取消收藏或载入画板二次编辑。
6. **Page 6：风格偏好与画像 (Style Profile & Analytics)**
   * 左侧配置常穿风格、偏好/避雷色系、常驻城市、默认预算及「切换账号」按钮；右侧实时渲染衣橱品类分布环形图、隐性风格倾向进度条与主色盘比例条。

---

## 4. 系统技术架构与核心算法设计 (Technical Architecture)

### 4.1 技术栈选型（严格对齐任务书）
* **前端 (Frontend)**：Vue 3 (`<script setup>`) + Vite 5 + TypeScript + Tailwind CSS + `shadcn-vue` + Lucide Vue Next + Vue Router 4 + Pinia + Axios + ECharts。
* **后端 (Backend)**：Java 17 + Spring Boot 3.2.x + MyBatis-Plus 3.5.x + Spring Validation + JJWT (`io.jsonwebtoken`) + Spring Security Crypto (`BCryptPasswordEncoder`) + Hutool + Jackson + OkHttp / WebClient。
* **数据库与存储**：MySQL 8.0；图片默认存本地静态资源目录（映射 `/uploads/**`），通过 `StorageService` 接口抽象便于扩展阿里云 OSS / MinIO。
* **第三方外部 API（毕业论文需明确注明来源）**：
  * **多模态视觉大模型**：通义千问 `qwen-vl-max` / `qwen-vl-plus`（阿里云 DashScope，兼容 OpenAI 协议）或智谱 `glm-4v-plus`。
  * **图像抠图分割**：阿里云视觉智能开放平台“服饰分割”接口或 Remove.bg API（含本地降级开关）。
  * **实时天气服务**：和风天气（QWeather）或高德天气开放 API（含本地缓存与 Mock 兜底）。

### 4.2 JWT 鉴权与多用户隔离架构
1. **签发流程**：用户调用 `/api/auth/login` 验证 `BCrypt` 密码通过后，`JwtUtil` 使用 HMAC-SHA256 生成包含 `userId`、`username`、签发时间与过期时间（7 天）的 JWT Token。
2. **后端拦截器 (`JwtAuthInterceptor`)**：
   * 放行白名单：`/api/auth/login`、`/api/auth/register`、`/uploads/**`、`/api/health`。
   * 对其余 `/api/**` 请求，从请求头提取 `Authorization: Bearer <token>`，验签成功后将 `userId` 写入 `UserContextHolder`（基于 `ThreadLocal`），在 `afterCompletion` 中清理避免内存泄漏；验签失败或缺失则直接返回 HTTP 401（业务码 `40101`）。
3. **前端 Axios 拦截器**：
   * 请求拦截器自动从 Pinia / `localStorage` 读取 Token 注入请求头。
   * 响应拦截器捕获 `401` 状态码时自动清空登录态、弹出提示并重定向至 `/login`。

### 4.3 后端核心流水线与个性化推荐算法
1. **衣物识别流水线 (`VisionRecognitionService`)**：
   `MultipartFile 格式/魔数/大小校验` $\rightarrow$ `图片缩放与抠图预处理（超时降级）` $\rightarrow$ `拼接结构化 Prompt 调用多模态大模型` $\rightarrow$ `JSON 清洗与枚举白名单校验` $\rightarrow$ `返回预览 DTO 供人工复核`。
2. **个性化加权推荐算法 (`OutfitRecommendationService`)**：
   * **阶段一：硬约束过滤**：根据实时气温 $T$ 过滤掉厚薄等级严重不符的衣物（如 $T \ge 25^\circ\text{C}$ 过滤 `thickness = 3` 的冬装，$T \le 10^\circ\text{C}$ 过滤纯夏装），并剔除用户避雷色系中权重过低的单品。
   * **阶段二：候选单品个性化打分**：对当前用户衣橱中的每件候选衣物 $i$ 计算综合适配得分 $S(i)$：
     $$S(i) = w_1 \cdot \text{TempMatch}(i, T) + w_2 \cdot \text{StylePref}(i, P) + w_3 \cdot \text{ColorPref}(i, P) + w_4 \cdot \text{SceneMatch}(i, C) - w_5 \cdot \text{RecentWearPenalty}(i, H)$$
     其中：
     * $\text{TempMatch}(i, T) \in [0, 1]$：衣物适用季节与厚薄等级对当前温度 $T$ 的匹配度；
     * $\text{StylePref}(i, P) \in [0, 1]$：衣物风格标签与用户显性风格及隐性偏好权重 `style_weights` 的加权余弦/重合度；
     * $\text{ColorPref}(i, P) \in [0, 1]$：主色调对用户偏好色系的命中度（命中避雷色系直接置 $0$）；
     * $\text{SceneMatch}(i, C) \in [0, 1]$：单品风格与当前出行场景 $C$ 的先验映射分；
     * $\text{RecentWearPenalty}(i, H) \in [0, 1]$：基于 `outfit_history` 计算近期 7 天内高频穿着惩罚项，提升推荐多样性与低频衣物唤醒率。
     * 默认权重设定：$w_1 = 0.30, w_2 = 0.25, w_3 = 0.15, w_4 = 0.30$（满足 $\sum_{k=1}^{4} w_k = 1$），多样性惩罚系数 $w_5 = 0.15$。
   * **阶段三：大模型精排与全身协同搭配**：按品类取 Top-$N$（合计不超过 25 件）高分候选单品摘要，结合锁定锚点、自然语言指令与实时天气发送给大模型，由大模型输出色彩和谐、版型协调的最终单品 ID 组合、主题标题、搭配理由以及 `missingItems`（缺失单品描述）。
   * **阶段四：预算内缺品商品召回 (`CommerceMatchService`)**：若 `missingItems` 非空，按品类、价格 $\le \text{budgetLimit}$ 及关键词匹配度检索 `mock_product` 表，封装外链商品列表返回前端。

### 4.4 前后端推荐工程目录结构

```text
ai-wardrobe/
├── backend/                                # SpringBoot 3 + Java 17 后端工程
│   ├── src/main/java/com/wardrobe/
│   │   ├── AiWardrobeApplication.java
│   │   ├── common/                         # Result<T>、ErrorCode、GlobalExceptionHandler
│   │   ├── config/                         # WebMvcConfig、JwtAuthInterceptor、CorsConfig
│   │   ├── security/                       # JwtUtil、UserContextHolder、PasswordUtil
│   │   ├── controller/                     # AuthController、WardrobeController、VisionController、OutfitController、PreferenceController、WeatherController
│   │   ├── service/                        # 核心业务逻辑与 AI 编排服务
│   │   │   ├── client/                     # MultimodalLlmClient、CutoutApiClient、WeatherApiClient
│   │   │   └── impl/                       # 推荐算法打分、灵感拆解、商品召回实现
│   │   ├── mapper/                         # MyBatis-Plus Mapper 接口
│   │   └── model/                          # entity/、dto/、vo/
│   └── src/main/resources/
│       ├── application.yml                 # 数据库、JWT 密钥、第三方 API 配置
│       └── sql/
│           ├── schema.sql                  # 6 张表结构定义
│           └── seed_data.sql               # 演示账号、衣物与模拟商品初始化脚本
└── frontend/                               # Vue 3 + Vite + TS + Tailwind 前端工程
    ├── src/
    │   ├── api/                            # axios 实例（含 JWT 拦截）及模块 API
    │   ├── router/                         # Vue Router 路由配置与登录守卫
    │   ├── stores/                         # Pinia 状态（authStore、wardrobeStore、lookbookStore）
    │   ├── components/                     # 通用 UI（shadcn-vue 组件、ItemDrawer、FlatLayCanvas）
    │   └── views/                          # AuthView、DashboardView、VisionStudioView、WardrobeView、LookbookView、CalendarView、ProfileView
    └── tailwind.config.js                  # 暖燕麦 Design Tokens 配置
```

---

## 5. 数据库设计 (MySQL 8.0)

### 5.1 表关系说明
严格包含毕业设计任务书指定的 **5 张核心业务表**，并扩展 **1 张模拟电商商品表** 支撑缺品导购闭环：
* `sys_user` (1) $\longleftrightarrow$ (1) `user_preference`
* `sys_user` (1) $\longleftrightarrow$ (N) `wardrobe_item`
* `sys_user` (1) $\longleftrightarrow$ (N) `outfit_plan`
* `sys_user` (1) $\longleftrightarrow$ (N) `outfit_history`
* `outfit_plan` (1) $\longleftrightarrow$ (N) `outfit_history`
* `mock_product`：平台共享商品表，通过 JSON 字段与 `outfit_plan.recommended_product_ids` 关联。

### 5.2 完整建表 SQL (`schema.sql`)

```sql
CREATE DATABASE IF NOT EXISTS ai_wardrobe
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
USE ai_wardrobe;

-- 1. 用户表 (sys_user)
CREATE TABLE `sys_user` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户主键ID',
  `username` VARCHAR(50) NOT NULL COMMENT '登录用户名（唯一）',
  `password` VARCHAR(255) NOT NULL COMMENT 'BCrypt加密密码',
  `nickname` VARCHAR(50) NOT NULL COMMENT '用户昵称',
  `avatar_url` VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
  `gender` TINYINT DEFAULT 0 COMMENT '性别：0未知 1男 2女',
  `email` VARCHAR(100) DEFAULT NULL COMMENT '联系邮箱',
  `status` TINYINT DEFAULT 1 COMMENT '账号状态：1正常 0禁用',
  `last_login_at` DATETIME DEFAULT NULL COMMENT '最近登录时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 2. 用户偏好表 (user_preference)
CREATE TABLE `user_preference` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '偏好主键ID',
  `user_id` BIGINT NOT NULL COMMENT '关联用户ID',
  `default_city` VARCHAR(50) NOT NULL DEFAULT '武汉' COMMENT '默认常驻城市（用于天气查询）',
  `favorite_styles` JSON DEFAULT NULL COMMENT '显性偏好风格列表，如 ["极简通勤","温柔韩系"]',
  `favorite_colors` JSON DEFAULT NULL COMMENT '偏好色系，如 ["米白","燕麦","牛仔蓝"]',
  `disliked_colors` JSON DEFAULT NULL COMMENT '避雷色系，如 ["荧光绿"]',
  `preferred_scenes` JSON DEFAULT NULL COMMENT '高频出行场景，如 ["职场通勤","周末约会"]',
  `budget_limit` DECIMAL(10,2) NOT NULL DEFAULT 300.00 COMMENT '默认单品购买预算上限(元)',
  `style_weights` JSON DEFAULT NULL COMMENT '隐性风格偏好权重映射（基于收藏与历史动态更新）',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pref_user_id` (`user_id`),
  CONSTRAINT `fk_pref_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户偏好表';

-- 3. 衣物信息表 (wardrobe_item)
CREATE TABLE `wardrobe_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '衣物主键ID',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID',
  `name` VARCHAR(100) NOT NULL COMMENT '衣物名称，如：米白针织开衫',
  `category` VARCHAR(30) NOT NULL COMMENT '一级品类：outerwear/top/bottom/shoes/accessory/dress',
  `sub_category` VARCHAR(50) DEFAULT NULL COMMENT '细分款式，如：针织开衫、直筒牛仔裤',
  `color` VARCHAR(30) NOT NULL COMMENT '主色调名称，如：米白、焦糖棕',
  `color_hex` VARCHAR(16) DEFAULT '#EFECE6' COMMENT '主色调HEX色值（用于前端色点与色盘统计）',
  `season` VARCHAR(50) NOT NULL COMMENT '适用季节：spring,summer,autumn,winter（逗号分隔）',
  `thickness` TINYINT NOT NULL DEFAULT 2 COMMENT '厚薄等级：1薄(夏) 2适中(春秋) 3厚(冬)',
  `style_tags` JSON DEFAULT NULL COMMENT '风格标签JSON数组，如 ["温柔韩系","极简通勤"]',
  `material` VARCHAR(50) DEFAULT NULL COMMENT '材质，如：纯棉、羊毛、牛仔',
  `original_image_url` VARCHAR(500) DEFAULT NULL COMMENT '原始上传图片URL',
  `processed_image_url` VARCHAR(500) NOT NULL COMMENT '展示图URL（优先存储透明底抠图）',
  `source_type` VARCHAR(20) NOT NULL DEFAULT 'photo' COMMENT '来源：photo(实物拍摄)/screenshot(订单截图)/manual(手动)',
  `ai_raw_result` JSON DEFAULT NULL COMMENT '多模态模型原始识别JSON（用于论文误差分析）',
  `is_manually_corrected` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否人工修正：0否 1是',
  `wear_count` INT NOT NULL DEFAULT 0 COMMENT '累计穿着次数',
  `last_worn_date` DATE DEFAULT NULL COMMENT '最近穿着日期',
  `remark` VARCHAR(255) DEFAULT NULL COMMENT '用户备注',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_item_user_cat` (`user_id`, `category`),
  KEY `idx_item_user_color` (`user_id`, `color`),
  CONSTRAINT `fk_item_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='衣物信息表';

-- 4. 穿搭方案表 (outfit_plan)
CREATE TABLE `outfit_plan` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '穿搭方案主键ID',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID',
  `theme_title` VARCHAR(100) NOT NULL COMMENT '方案主题标题，如：温柔通勤 · 轻松出门',
  `trigger_type` VARCHAR(30) NOT NULL COMMENT '触发类型：daily_weather/nl_query/anchor_item/inspiration_img',
  `scene_tag` VARCHAR(50) DEFAULT NULL COMMENT '适用场景标签：职场通勤/周末约会/互联网面试/休闲',
  `weather_snapshot` JSON DEFAULT NULL COMMENT '生成时天气快照，如 {"city":"武汉","temp":18,"condition":"多云"}',
  `user_query` VARCHAR(500) DEFAULT NULL COMMENT '自然语言指令或微调记录',
  `inspiration_image_url` VARCHAR(500) DEFAULT NULL COMMENT '灵感拆解模式上传的原图URL',
  `item_ids` JSON NOT NULL COMMENT '调用的已有衣橱衣物ID列表，如 [2, 4, 7, 9]',
  `missing_items` JSON DEFAULT NULL COMMENT 'AI判定的缺失单品槽位描述数组',
  `recommended_product_ids` JSON DEFAULT NULL COMMENT '关联召回的模拟商品ID列表',
  `ai_reasoning` TEXT NOT NULL COMMENT 'AI造型师搭配理由与色彩/温度解析',
  `match_score` DECIMAL(5,2) DEFAULT NULL COMMENT '综合推荐匹配得分(0-100)',
  `is_favorite` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否收藏：0否 1是',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
  PRIMARY KEY (`id`),
  KEY `idx_plan_user_fav` (`user_id`, `is_favorite`),
  KEY `idx_plan_user_time` (`user_id`, `created_at`),
  CONSTRAINT `fk_plan_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='穿搭方案表';

-- 5. 穿搭历史表 (outfit_history)
CREATE TABLE `outfit_history` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '历史记录主键ID',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID',
  `plan_id` BIGINT DEFAULT NULL COMMENT '关联穿搭方案ID（可空）',
  `wear_date` DATE NOT NULL COMMENT '实际穿着日期',
  `scene_tag` VARCHAR(50) DEFAULT NULL COMMENT '实际出行场景',
  `weather_info` VARCHAR(100) DEFAULT NULL COMMENT '当日天气简述，如：多云 18℃',
  `item_ids` JSON NOT NULL COMMENT '当日实际穿着衣物ID数组',
  `feedback_score` TINYINT DEFAULT NULL COMMENT '满意度打分：1-5星（反哺偏好权重）',
  `notes` VARCHAR(300) DEFAULT NULL COMMENT '个人穿搭心得笔记',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_history_user_date` (`user_id`, `wear_date`),
  CONSTRAINT `fk_history_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_history_plan` FOREIGN KEY (`plan_id`) REFERENCES `outfit_plan` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='穿搭历史表';

-- 6. 模拟电商商品表 (mock_product - 支撑缺品补全与预算导购闭环)
CREATE TABLE `mock_product` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '商品主键ID',
  `title` VARCHAR(150) NOT NULL COMMENT '商品标题',
  `category` VARCHAR(30) NOT NULL COMMENT '品类：outerwear/top/bottom/shoes/accessory/dress',
  `color` VARCHAR(30) DEFAULT NULL COMMENT '主色调',
  `color_hex` VARCHAR(16) DEFAULT '#DFD3C3' COMMENT '主色调HEX色值',
  `style_tags` JSON DEFAULT NULL COMMENT '风格标签数组',
  `price` DECIMAL(10,2) NOT NULL COMMENT '价格(元)',
  `platform` VARCHAR(20) NOT NULL COMMENT '平台标识：taobao/jd/pdd',
  `image_url` VARCHAR(500) NOT NULL COMMENT '商品主图URL',
  `product_url` VARCHAR(500) NOT NULL COMMENT '外部购买跳转链接',
  `description` VARCHAR(500) DEFAULT NULL COMMENT '款式描述与推荐理由（用于检索匹配）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
  PRIMARY KEY (`id`),
  KEY `idx_product_cat_price` (`category`, `price`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模拟电商商品库';
```

### 5.3 初始化演示数据参考 (`seed_data.sql`)

```sql
USE ai_wardrobe;

-- 预置演示账号（username: demo_user，明文密码 Wardrobe2026! 需在启动类用 BCryptPasswordEncoder 写入或替换下方占位哈希）
INSERT INTO `sys_user` (`id`, `username`, `password`, `nickname`, `gender`, `email`) VALUES
(1, 'demo_user', '$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5E1f/EXAMPLEHASHREPLACEATBOOT', '林小满', 2, 'demo@aiwardrobe.local');

INSERT INTO `user_preference` (`user_id`, `default_city`, `favorite_styles`, `favorite_colors`, `disliked_colors`, `preferred_scenes`, `budget_limit`, `style_weights`) VALUES
(1, '武汉', '["极简通勤","温柔韩系","Clean Fit"]', '["米白","燕麦","牛仔蓝","焦糖棕"]', '["荧光绿"]', '["职场通勤","周末约会","互联网面试"]', 300.00, '{"极简通勤":0.92,"温柔韩系":0.85,"Clean Fit":0.78}');

-- 预置核心基础款衣物（与交互原型保持一致）
INSERT INTO `wardrobe_item` (`user_id`, `name`, `category`, `sub_category`, `color`, `color_hex`, `season`, `thickness`, `style_tags`, `material`, `processed_image_url`, `source_type`, `wear_count`) VALUES
(1, '米白针织开衫', 'outerwear', '针织开衫', '米白', '#F3EDE2', 'spring,autumn', 2, '["温柔韩系","极简通勤","针织"]', '棉羊毛混纺', '/uploads/seed/cardigan.png', 'photo', 8),
(1, '基础重磅白T', 'top', '短袖T恤', '纯白', '#FFFFFF', 'spring,summer,autumn', 1, '["Clean Fit","极简通勤","百搭"]', '260g精梳纯棉', '/uploads/seed/white_tee.png', 'screenshot', 14),
(1, '炭黑V领针织背心', 'top', '针织马甲', '炭黑', '#262628', 'spring,autumn,winter', 2, '["学院风","极简通勤"]', '细针羊毛', '/uploads/seed/knit_vest.png', 'photo', 6),
(1, '深蓝直筒牛仔裤', 'bottom', '直筒牛仔裤', '牛仔蓝', '#3B5372', 'spring,summer,autumn,winter', 2, '["Clean Fit","日常休闲","百搭"]', '全棉丹宁', '/uploads/seed/jeans.png', 'screenshot', 12),
(1, '黑色垂感西装阔腿裤', 'bottom', '西装阔腿裤', '炭黑', '#262628', 'spring,autumn,winter', 2, '["极简通勤","干练面试"]', 'TR抗皱斜纹', '/uploads/seed/trousers.png', 'photo', 9),
(1, '卡其高腰百褶半身裙', 'bottom', '百褶半身裙', '卡其', '#C7B299', 'spring,summer,autumn', 1, '["温柔韩系","学院风","约会"]', '聚酯纤维混纺', '/uploads/seed/skirt.png', 'photo', 5),
(1, '焦糖棕皮质乐福鞋', 'shoes', '乐福鞋', '焦糖棕', '#8A5230', 'spring,autumn,winter', 2, '["极简通勤","美拉德复古","学院风"]', '头层牛皮', '/uploads/seed/loafers.png', 'screenshot', 11),
(1, '复古米灰德训鞋', 'shoes', '德训鞋', '燕麦', '#DFD3C3', 'spring,summer,autumn,winter', 2, '["Clean Fit","日常休闲"]', '翻毛皮拼接', '/uploads/seed/sneakers.png', 'photo', 10),
(1, '燕麦帆布拼皮托特包', 'accessory', '托特包', '燕麦', '#DFD3C3', 'spring,summer,autumn,winter', 2, '["极简通勤","温柔韩系"]', '帆布拼牛皮', '/uploads/seed/tote.png', 'photo', 15),
(1, '浅燕麦微廓形西装', 'outerwear', '西装外套', '燕麦', '#DFD3C3', 'spring,autumn', 2, '["极简通勤","干练面试"]', '精纺混纺', '/uploads/seed/blazer.png', 'photo', 7);

-- 预置模拟商品库（覆盖多品类与不同预算梯度）
INSERT INTO `mock_product` (`title`, `category`, `color`, `color_hex`, `style_tags`, `price`, `platform`, `image_url`, `product_url`, `description`) VALUES
('经典英伦双排扣米色短款风衣', 'outerwear', '燕麦', '#DFD3C3', '["极简通勤","英伦风"]', 269.00, 'taobao', '/uploads/products/trench_short.png', 'https://item.taobao.com/demo/101', '短款显高，完美适配微凉 18℃ 通勤叠穿，与牛仔裤及白T极搭'),
('复古美拉德翻毛麂皮工装夹克', 'outerwear', '焦糖棕', '#8A5230', '["美拉德复古","工装休闲"]', 289.00, 'taobao', '/uploads/products/suede_jacket.png', 'https://item.taobao.com/demo/102', '秋日美拉德核心外套，轻松点亮基础款白T+深蓝牛仔'),
('极简法式条纹海魂衫长袖打底', 'top', '牛仔蓝', '#3B5372', '["法式松弛","Clean Fit"]', 129.00, 'pdd', '/uploads/products/striped_shirt.png', 'https://mobile.yangkeduo.com/demo/103', '高性价比内搭平替，增添法式松弛感'),
('轻奢垂感卡其长款防风风衣', 'outerwear', '卡其', '#C7B299', '["极简通勤","大女主"]', 459.00, 'jd', '/uploads/products/trench_long.png', 'https://item.jd.com/demo/104', '防风抗皱高支斜纹面料，适合较高预算职场进阶'),
('复古厚底深棕漆皮乐福鞋平替', 'shoes', '焦糖棕', '#8A5230', '["极简通勤","学院风"]', 178.00, 'pdd', '/uploads/products/loafers_alt.png', 'https://mobile.yangkeduo.com/demo/106', '200元内质感通勤鞋履，软底不磨脚'),
('哑光软皮大容量极简通勤托特包', 'accessory', '炭黑', '#262628', '["极简通勤","干练面试"]', 158.00, 'taobao', '/uploads/products/black_tote.png', 'https://item.taobao.com/demo/107', '可装14寸笔记本，与黑西装裤呼应显高级');
```

---

## 6. RESTful API 契约与 Prompt 工程规范

### 6.1 统一响应格式与业务错误码

所有接口均返回统一的 `Result<T>` JSON 结构：

```json
{
  "code": 200,
  "message": "success",
  "data": {},
  "timestamp": 1763850000000
}
```

| 业务状态码 | HTTP 状态码 | 含义说明 | 前端处理策略 |
| :--- | :--- | :--- | :--- |
| `200` | 200 | 请求成功 | 正常渲染业务数据 |
| `40001` | 400 | 请求参数校验失败（如密码少于 6 位、必填字段为空） | 表单行内红色提示 |
| `40002` | 400 | 图片格式非法、魔数校验失败或超出 $10\text{MB}$ 上限 | 弹窗拦截并提示允许格式 |
| `40003` | 400 | 用户名已存在（注册冲突） | 提示更换用户名或去登录 |
| `40101` | 401 | JWT 缺失、过期或签名无效 | 清除 Token 并跳转登录页 |
| `40102` | 401 | 用户名或密码错误 | 登录卡片内错误提示 |
| `40301` | 403 | 越权访问他人衣橱/穿搭资源 | 拦截并提示无操作权限 |
| `42201` | 422 | 多模态模型判定上传图片非服装主体 (`isClothing=false`) | 提示重新上传清晰衣物图 |
| `50201` | 502 | 多模态大模型接口超时或结果无法解析 | 提示稍后重试或转入手动录入 |

### 6.2 核心接口清单

| 模块 | 方法 | 接口路径 | 鉴权 | 核心请求参数 / 功能说明 |
| :--- | :--- | :--- | :--- | :--- |
| **认证** | `POST` | `/api/auth/register` | 否 | `{username, nickname, password, defaultCity, loadStarterPack}` $\rightarrow$ 注册并直接签发 JWT |
| **认证** | `POST` | `/api/auth/login` | 否 | `{username, password}` $\rightarrow$ 返回 `{token, expiresIn, userInfo}` |
| **认证** | `GET` | `/api/auth/me` | 是 | 校验当前 Token 并返回最新用户信息与衣橱概览计数 |
| **偏好** | `GET` / `PUT` | `/api/preferences` | 是 | 获取/更新用户偏好设置及隐性风格画像统计数据 |
| **视觉** | `POST` | `/api/vision/recognize-item` | 是 | `multipart/form-data` 上传单品图/订单截图 $\rightarrow$ 返回原图URL、抠图URL、AI 原始 JSON 与结构化属性表单 |
| **视觉** | `POST` | `/api/vision/analyze-inspiration` | 是 | 上传街拍图 + `userQuery` + `budgetLimit` $\rightarrow$ 拆解单品并检索当前用户衣橱与预算内平替 |
| **衣橱** | `GET` | `/api/wardrobe/items` | 是 | 支持 `category`、`color`、`season`、`keyword` 多维组合筛选与排序 |
| **衣橱** | `POST` | `/api/wardrobe/items` | 是 | 确认衣物入库（携带 `isManuallyCorrected` 与 `aiRawResult`） |
| **衣橱** | `GET` / `PUT` / `DELETE` | `/api/wardrobe/items/{id}` | 是 | 查看单品详情（附带关联的历史穿搭列表）、更新属性、删除单品 |
| **天气** | `GET` | `/api/weather/current` | 是 | `?city=武汉` $\rightarrow$ 返回实时温度、天气描述、图标标识与穿衣指数 |
| **推荐** | `POST` | `/api/outfit/recommend` | 是 | `{triggerType, sceneTag, userQuery, anchorItemId, budgetLimit, refineInstruction, basePlanId}` $\rightarrow$ 生成/微调穿搭方案 |
| **推荐** | `POST` | `/api/outfit/plans/{id}/swap-slot` | 是 | `{category, excludeItemIds}` $\rightarrow$ 局部换一件同品类已有单品 |
| **方案** | `GET` / `PATCH` / `DELETE` | `/api/outfit/plans` | 是 | 查询方案与收藏列表、切换收藏 (`PATCH /{id}/favorite`)、删除方案 |
| **历史** | `GET` / `POST` / `PUT` | `/api/outfit/history` | 是 | 按月查询日历 (`?month=2026-11`)、采纳方案记为今日穿搭、更新心得与星级评分 |

### 6.3 多模态与造型师 Prompt 工程规范

所有大模型调用在 System Prompt 中均须声明**仅返回合法 JSON，禁止输出 Markdown 围栏与额外解释**：

1. **单品识别 System Prompt 输出模板**：
```json
{
  "isClothing": true,
  "name": "米白针织开衫",
  "category": "outerwear",
  "subCategory": "针织开衫",
  "color": "米白",
  "colorHex": "#F3EDE2",
  "season": "spring,autumn",
  "thickness": 2,
  "styleTags": ["温柔韩系", "极简通勤"],
  "material": "棉羊毛混纺"
}
```

2. **街拍灵感拆解 System Prompt 输出模板**：
```json
{
  "overallStyle": "韩系松弛通勤",
  "detectedItems": [
    {
      "category": "outerwear",
      "name": "米色双排扣短款风衣",
      "color": "燕麦",
      "styleTags": ["极简通勤", "英伦风"],
      "keywords": "米色 短款 双排扣 风衣"
    },
    {
      "category": "bottom",
      "name": "高腰深蓝直筒牛仔裤",
      "color": "牛仔蓝",
      "styleTags": ["Clean Fit", "日常休闲"],
      "keywords": "深蓝 直筒 牛仔裤"
    }
  ]
}
```

3. **造型师精排推荐 System Prompt 输出模板**：
```json
{
  "themeTitle": "温柔通勤 · 轻松出门",
  "selectedItemIds": [2, 4, 7, 9],
  "missingItems": [
    {
      "category": "outerwear",
      "name": "米色短款双排扣风衣",
      "color": "燕麦",
      "keywords": "米色 短款 风衣 通勤",
      "reason": "当前 18℃ 多云微凉，建议外搭短款风衣提升腰线并抵御晚风"
    }
  ],
  "aiReasoning": "以重磅白T与深蓝直筒牛仔裤构建利落基底，焦糖乐福鞋与燕麦托特包形成低饱和大地色系呼应，兼顾 18℃ 叠穿舒适度与通勤高级感。",
  "matchScore": 96.0
}
```

---

## 7. 系统测试与效果分析方案（对齐毕业论文测试章节）

系统开发完成后，围绕以下 **5 个维度** 开展测试并汇总定量指标，可直接写入毕业论文的“系统测试与效果分析”章节：

| 测试维度 | 核心测试场景与用例构造 | 评估指标 / 预期结果 |
| :--- | :--- | :--- |
| **1. 认证鉴权与越权隔离测试** | 正常注册/登录、错误密码拦截、Token 过期 401 跳转；构造账号 B 的 Token 请求修改/删除账号 A 的衣物 ID | 密码均以 BCrypt 密文存库；越权请求 100% 返回 `40301` 且数据零泄露 |
| **2. 多模态衣物识别准确率测试** | 准备 40 张测试图（覆盖 5 大品类、复杂实拍背景与电商订单截图），记录识别结果与耗时 | 统计**品类准确率、颜色准确率、风格合理率**及平均耗时；验证人工修正回写 `is_manually_corrected=1` 生效 |
| **3. 多场景个性化推荐效果测试** | 覆盖 `18℃多云+通勤`、`26℃晴朗+约会`、`9℃小雨+面试`、`锚点一衣多搭`、`连续 5 次推荐多样性` | 验证温度过滤准确性、显性/避雷色系遵守率、近期穿着惩罚项触发后的多样性提升及预算过滤准确性 |
| **4. 核心业务与数据一致性测试** | 衣物增删改查、多维组合筛选、收藏状态切换、记录今日穿搭联动更新单品 `wear_count` 与日历视图 | 前后端数据强一致，单品详情抽屉反查历史穿搭记录准确无误 |
| **5. 异常处理与降级鲁棒性测试** | 上传 `.txt` 改名为 `.jpg` 的伪装文件、上传 $>10\text{MB}$ 图片、上传非衣物风景照、模拟抠图/天气/大模型超时 | 全局异常处理器精准返回对应业务码与友好提示；抠图与天气超时自动降级，系统不崩溃 |

---

## 8. AI 辅助编程分阶段推进指南 (AI Coding Roadmap)

将本文档作为上下文投喂给 AI 编程工具（Cursor / Claude / Trae）时，建议严格按以下 **4 个阶段** 分步执行：

* **Phase 1：工程脚手架、数据库与 JWT 登录闭环**
  * 按第 4.4 节目录初始化 SpringBoot 3 与 Vue 3 + Tailwind + `shadcn-vue` 工程。
  * 执行 `schema.sql` 与 `seed_data.sql`（启动时自动用 `BCryptPasswordEncoder` 初始化演示账号 `demo_user`）。
  * 完成 `/api/auth/register`、`/api/auth/login`、`/api/auth/me`、后端 `JwtAuthInterceptor` 以及前端 **Page 0 杂志风左右分栏登录/注册页**（含演示账号一键登录）与路由守卫，验证多账号切换与数据隔离。
* **Phase 2：杂志画廊 UI 还原与数字衣橱/偏好 CRUD**
  * 依据第 3 节 Design Tokens 还原 Bento Grid 首页、数字衣橱画廊（含色系圆点筛选与右侧滑出详情抽屉）、穿搭日历与收藏馆、以及风格偏好画像页（集成 ECharts）。
  * 贯通衣橱单品增删改查、偏好更新及本地文件上传静态映射。
* **Phase 3：多模态大模型、抠图与天气服务集成**
  * 封装 `MultimodalLlmClient`、`CutoutApiClient`（带降级开关）与 `WeatherApiClient`（带缓存兜底）。
  * 落地 **5 步引导单品入库**（含原图/抠图对比与胶囊人工修正）及 **街拍灵感拆解 $\rightarrow$ 衣橱相似度检索 + 预算内商品推荐** 完整链路，补全全局异常拦截器。
* **Phase 4：个性化推荐算法、平铺画板交互与论文测试**
  * 实现第 4.3 节候选单品加权打分公式与大模型造型师精排，打通智能搭配画板的 **Flat-Lay 平铺拼贴、局部换一件、多轮微调对话、收藏与日历反馈闭环**。
  * 逐项执行第 7 节的 5 维度测试用例，收集识别准确率、响应耗时与推荐案例截图用于毕业论文撰写与答辩演示。