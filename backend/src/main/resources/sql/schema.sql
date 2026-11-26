CREATE DATABASE IF NOT EXISTS ai_wardrobe
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
USE ai_wardrobe;

-- 1. 用户表 (sys_user)
CREATE TABLE IF NOT EXISTS `sys_user` (
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
CREATE TABLE IF NOT EXISTS `user_preference` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '偏好主键ID',
  `user_id` BIGINT NOT NULL COMMENT '关联用户ID',
  `default_city` VARCHAR(50) NOT NULL DEFAULT '武汉' COMMENT '默认常驻城市（用于天气查询）',
  `favorite_styles` JSON DEFAULT NULL COMMENT '显性偏好风格列表',
  `favorite_colors` JSON DEFAULT NULL COMMENT '偏好色系',
  `disliked_colors` JSON DEFAULT NULL COMMENT '避雷色系',
  `preferred_scenes` JSON DEFAULT NULL COMMENT '高频出行场景',
  `budget_limit` DECIMAL(10,2) NOT NULL DEFAULT 300.00 COMMENT '默认单品购买预算上限(元)',
  `body_shape` VARCHAR(40) DEFAULT 'H型直筒' COMMENT '个人身形档案：H型直筒/X型沙漏/A型梨形/Y型倒三角/O型苹果',
  `color_season` VARCHAR(40) DEFAULT '中性百搭' COMMENT '四季色彩季型：春季暖亮/夏季冷柔/秋季暖深/冬季冷艳/中性百搭',
  `height_cm` INT DEFAULT 165 COMMENT '身高(cm)',
  `style_weights` JSON DEFAULT NULL COMMENT '隐性风格偏好权重映射',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pref_user_id` (`user_id`),
  CONSTRAINT `fk_pref_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户偏好表';

-- 3. 衣物信息表 (wardrobe_item)
CREATE TABLE IF NOT EXISTS `wardrobe_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '衣物主键ID',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID',
  `name` VARCHAR(100) NOT NULL COMMENT '衣物名称',
  `category` VARCHAR(30) NOT NULL COMMENT '一级品类：outerwear/top/bottom/shoes/accessory/dress',
  `sub_category` VARCHAR(50) DEFAULT NULL COMMENT '细分款式',
  `color` VARCHAR(30) NOT NULL COMMENT '主色调名称',
  `color_hex` VARCHAR(16) DEFAULT '#EFECE6' COMMENT '主色调HEX色值',
  `season` VARCHAR(50) NOT NULL COMMENT '适用季节：spring,summer,autumn,winter',
  `thickness` TINYINT NOT NULL DEFAULT 2 COMMENT '厚薄等级：1薄 2适中 3厚',
  `style_tags` JSON DEFAULT NULL COMMENT '风格标签JSON数组',
  `material` VARCHAR(50) DEFAULT NULL COMMENT '材质',
  `svg_type` VARCHAR(40) DEFAULT NULL COMMENT '矢量占位图示标识（用于预置示例或无图降级）',
  `original_image_url` VARCHAR(500) DEFAULT NULL COMMENT '原始上传图片URL',
  `processed_image_url` VARCHAR(500) NOT NULL COMMENT '展示图URL（优先存储透明底抠图）',
  `source_type` VARCHAR(20) NOT NULL DEFAULT 'photo' COMMENT '来源：photo/screenshot/manual',
  `ai_raw_result` JSON DEFAULT NULL COMMENT '多模态模型原始识别JSON',
  `is_manually_corrected` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否人工修正：0否 1是',
  `price` DECIMAL(10,2) DEFAULT NULL COMMENT '单品购入价格(元)，用于CPW单次穿着成本计算',
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
CREATE TABLE IF NOT EXISTS `outfit_plan` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '穿搭方案主键ID',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID',
  `theme_title` VARCHAR(100) NOT NULL COMMENT '方案主题标题',
  `trigger_type` VARCHAR(30) NOT NULL COMMENT '触发类型：daily_weather/nl_query/anchor_item/inspiration_img',
  `scene_tag` VARCHAR(50) DEFAULT NULL COMMENT '适用场景标签',
  `weather_snapshot` JSON DEFAULT NULL COMMENT '生成时天气快照',
  `user_query` VARCHAR(500) DEFAULT NULL COMMENT '自然语言指令或微调记录',
  `inspiration_image_url` VARCHAR(500) DEFAULT NULL COMMENT '灵感拆解模式上传的原图URL',
  `item_ids` JSON NOT NULL COMMENT '调用的已有衣橱衣物ID列表',
  `missing_items` JSON DEFAULT NULL COMMENT 'AI判定的缺失单品槽位描述数组',
  `recommended_product_ids` JSON DEFAULT NULL COMMENT '关联召回的模拟商品ID列表',
  `ai_reasoning` TEXT NOT NULL COMMENT 'AI造型师搭配理由',
  `match_score` DECIMAL(5,2) DEFAULT NULL COMMENT '综合推荐匹配得分(0-100)',
  `collage_layout` JSON DEFAULT NULL COMMENT '自由拼贴画板节点布局JSON',
  `try_on_image_url` VARCHAR(500) DEFAULT NULL COMMENT 'AI上身杂志预览图URL',
  `is_favorite` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否收藏：0否 1是',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
  PRIMARY KEY (`id`),
  KEY `idx_plan_user_fav` (`user_id`, `is_favorite`),
  KEY `idx_plan_user_time` (`user_id`, `created_at`),
  CONSTRAINT `fk_plan_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='穿搭方案表';

-- 5. 穿搭历史表 (outfit_history)
CREATE TABLE IF NOT EXISTS `outfit_history` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '历史记录主键ID',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID',
  `plan_id` BIGINT DEFAULT NULL COMMENT '关联穿搭方案ID（可空）',
  `theme_title` VARCHAR(100) DEFAULT NULL COMMENT '当日搭配标题',
  `wear_date` DATE NOT NULL COMMENT '实际穿着日期',
  `scene_tag` VARCHAR(50) DEFAULT NULL COMMENT '实际出行场景',
  `weather_info` VARCHAR(100) DEFAULT NULL COMMENT '当日天气简述',
  `weather_temp` INT DEFAULT NULL COMMENT '当日气温(℃)',
  `weather_icon` VARCHAR(30) DEFAULT 'cloud-sun' COMMENT '天气图标标识',
  `item_ids` JSON NOT NULL COMMENT '当日实际穿着衣物ID数组',
  `ai_reasoning` TEXT DEFAULT NULL COMMENT '当日AI搭配理由快照',
  `ootd_photo_url` VARCHAR(500) DEFAULT NULL COMMENT '当日真人OOTD实拍图URL',
  `feedback_score` TINYINT DEFAULT 5 COMMENT '满意度打分：1-5星',
  `notes` VARCHAR(300) DEFAULT NULL COMMENT '个人穿搭心得笔记',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_history_user_date` (`user_id`, `wear_date`),
  CONSTRAINT `fk_history_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_history_plan` FOREIGN KEY (`plan_id`) REFERENCES `outfit_plan` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='穿搭历史表';

-- 6. 模拟电商商品表 (mock_product)
CREATE TABLE IF NOT EXISTS `mock_product` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '商品主键ID',
  `title` VARCHAR(150) NOT NULL COMMENT '商品标题',
  `category` VARCHAR(30) NOT NULL COMMENT '品类',
  `color` VARCHAR(30) DEFAULT NULL COMMENT '主色调',
  `color_hex` VARCHAR(16) DEFAULT '#DFD3C3' COMMENT '主色调HEX色值',
  `svg_type` VARCHAR(40) DEFAULT NULL COMMENT '矢量图示标识',
  `style_tags` JSON DEFAULT NULL COMMENT '风格标签数组',
  `price` DECIMAL(10,2) NOT NULL COMMENT '价格(元)',
  `platform` VARCHAR(20) NOT NULL COMMENT '平台标识：taobao/jd/pdd',
  `image_url` VARCHAR(500) NOT NULL COMMENT '商品主图URL',
  `product_url` VARCHAR(500) NOT NULL COMMENT '外部购买跳转链接',
  `description` VARCHAR(500) DEFAULT NULL COMMENT '款式描述与推荐理由',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
  PRIMARY KEY (`id`),
  KEY `idx_product_cat_price` (`category`, `price`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模拟电商商品库';

-- 7. 用户模型 API 配置表 (user_ai_config - 前端可填写、服务端加密落库实时生效)
CREATE TABLE IF NOT EXISTS `user_ai_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '配置主键ID',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID',
  `provider_name` VARCHAR(50) NOT NULL DEFAULT 'dashscope' COMMENT '服务商标识：dashscope/zhipu/openai/deepseek/gemini/custom',
  `base_url` VARCHAR(500) NOT NULL DEFAULT 'https://dashscope.aliyuncs.com/compatible-mode/v1' COMMENT 'OpenAI兼容API Base URL',
  `api_key_encrypted` TEXT DEFAULT NULL COMMENT 'AES-256-GCM加密后的API Key密文',
  `api_key_masked` VARCHAR(100) DEFAULT NULL COMMENT '前端脱敏回显字符串',
  `vision_model` VARCHAR(100) NOT NULL DEFAULT 'qwen-vl-max' COMMENT '多模态视觉识别模型名',
  `chat_model` VARCHAR(100) NOT NULL DEFAULT 'qwen-plus' COMMENT '搭配推理模型名',
  `image_model` VARCHAR(100) DEFAULT 'gpt-image-1' COMMENT '上身预览生图模型名',
  `model_reference_url` VARCHAR(500) DEFAULT NULL COMMENT '用户本人/模特参考图URL',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用真实大模型调用',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_cfg_user_id` (`user_id`),
  CONSTRAINT `fk_ai_cfg_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户大模型API配置表';

-- 8. 灵感广场造型点赞收藏表 (inspo_like)
CREATE TABLE IF NOT EXISTS `inspo_like` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID',
  `look_id` VARCHAR(64) NOT NULL COMMENT '灵感造型ID',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inspo_like_user_look` (`user_id`, `look_id`),
  CONSTRAINT `fk_inspo_like_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='灵感造型点赞收藏表';

-- 9. AI 杂志上身预览异步任务与历史表 (try_on_job)
CREATE TABLE IF NOT EXISTS `try_on_job` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务主键ID',
  `user_id` BIGINT NOT NULL COMMENT '所属用户ID',
  `plan_id` BIGINT NOT NULL COMMENT '关联搭配方案ID',
  `plan_theme_title` VARCHAR(120) DEFAULT NULL COMMENT '方案主题快照',
  `status` VARCHAR(20) NOT NULL DEFAULT 'PROCESSING' COMMENT '状态：QUEUED/PROCESSING/SUCCEEDED/FAILED',
  `style_preset` VARCHAR(40) DEFAULT 'studio_white' COMMENT '风格预设',
  `generation_mode` VARCHAR(30) DEFAULT 'auto' COMMENT '生成模式：auto/hd/chat_stream',
  `use_reference_photo` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否请求携带参考照',
  `used_real_image_model` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否命中真实生图模型',
  `used_reference_photo` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否实际生效参考照',
  `model_used` VARCHAR(100) DEFAULT NULL COMMENT '使用的生图模型',
  `endpoint_used` VARCHAR(100) DEFAULT NULL COMMENT '实际调用的上游端点',
  `prompt_used` TEXT DEFAULT NULL COMMENT '完整提示词',
  `preview_image_url` VARCHAR(500) DEFAULT NULL COMMENT '生成图片持久化URL',
  `notice` VARCHAR(500) DEFAULT NULL COMMENT '结果提示文案',
  `error_message` VARCHAR(500) DEFAULT NULL COMMENT '失败原因说明',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_tryon_job_user_created` (`user_id`, `created_at`),
  KEY `idx_tryon_job_plan` (`plan_id`),
  CONSTRAINT `fk_tryon_job_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_tryon_job_plan` FOREIGN KEY (`plan_id`) REFERENCES `outfit_plan` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI上身预览异步任务与历史记录表';
