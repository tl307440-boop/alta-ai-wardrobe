CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `username` VARCHAR(50) NOT NULL,
  `password` VARCHAR(255) NOT NULL,
  `nickname` VARCHAR(50) NOT NULL,
  `avatar_url` VARCHAR(500) DEFAULT NULL,
  `gender` TINYINT DEFAULT 0,
  `email` VARCHAR(100) DEFAULT NULL,
  `status` TINYINT DEFAULT 1,
  `last_login_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
);

CREATE TABLE IF NOT EXISTS `user_preference` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `default_city` VARCHAR(50) NOT NULL DEFAULT '武汉',
  `favorite_styles` TEXT DEFAULT NULL,
  `favorite_colors` TEXT DEFAULT NULL,
  `disliked_colors` TEXT DEFAULT NULL,
  `preferred_scenes` TEXT DEFAULT NULL,
  `budget_limit` DECIMAL(10,2) NOT NULL DEFAULT 300.00,
  `style_weights` TEXT DEFAULT NULL,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pref_user_id` (`user_id`),
  CONSTRAINT `fk_pref_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS `wardrobe_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `category` VARCHAR(30) NOT NULL,
  `sub_category` VARCHAR(50) DEFAULT NULL,
  `color` VARCHAR(30) NOT NULL,
  `color_hex` VARCHAR(16) DEFAULT '#EFECE6',
  `season` VARCHAR(50) NOT NULL,
  `thickness` TINYINT NOT NULL DEFAULT 2,
  `style_tags` TEXT DEFAULT NULL,
  `material` VARCHAR(50) DEFAULT NULL,
  `svg_type` VARCHAR(40) DEFAULT NULL,
  `original_image_url` VARCHAR(500) DEFAULT NULL,
  `processed_image_url` VARCHAR(500) NOT NULL,
  `source_type` VARCHAR(20) NOT NULL DEFAULT 'photo',
  `ai_raw_result` TEXT DEFAULT NULL,
  `is_manually_corrected` TINYINT NOT NULL DEFAULT 0,
  `price` DECIMAL(10,2) DEFAULT NULL,
  `wear_count` INT NOT NULL DEFAULT 0,
  `last_worn_date` DATE DEFAULT NULL,
  `remark` VARCHAR(255) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_item_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS `outfit_plan` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `theme_title` VARCHAR(100) NOT NULL,
  `trigger_type` VARCHAR(30) NOT NULL,
  `scene_tag` VARCHAR(50) DEFAULT NULL,
  `weather_snapshot` TEXT DEFAULT NULL,
  `user_query` VARCHAR(500) DEFAULT NULL,
  `inspiration_image_url` VARCHAR(500) DEFAULT NULL,
  `item_ids` TEXT NOT NULL,
  `missing_items` TEXT DEFAULT NULL,
  `recommended_product_ids` TEXT DEFAULT NULL,
  `ai_reasoning` TEXT NOT NULL,
  `match_score` DECIMAL(5,2) DEFAULT NULL,
  `collage_layout` TEXT DEFAULT NULL,
  `try_on_image_url` VARCHAR(500) DEFAULT NULL,
  `is_favorite` TINYINT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_plan_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS `outfit_history` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `plan_id` BIGINT DEFAULT NULL,
  `theme_title` VARCHAR(100) DEFAULT NULL,
  `wear_date` DATE NOT NULL,
  `scene_tag` VARCHAR(50) DEFAULT NULL,
  `weather_info` VARCHAR(100) DEFAULT NULL,
  `weather_temp` INT DEFAULT NULL,
  `weather_icon` VARCHAR(30) DEFAULT 'cloud-sun',
  `item_ids` TEXT NOT NULL,
  `ai_reasoning` TEXT DEFAULT NULL,
  `feedback_score` TINYINT DEFAULT 5,
  `notes` VARCHAR(300) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_history_user_date` (`user_id`, `wear_date`),
  CONSTRAINT `fk_history_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_history_plan` FOREIGN KEY (`plan_id`) REFERENCES `outfit_plan` (`id`) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS `mock_product` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `title` VARCHAR(150) NOT NULL,
  `category` VARCHAR(30) NOT NULL,
  `color` VARCHAR(30) DEFAULT NULL,
  `color_hex` VARCHAR(16) DEFAULT '#DFD3C3',
  `svg_type` VARCHAR(40) DEFAULT NULL,
  `style_tags` TEXT DEFAULT NULL,
  `price` DECIMAL(10,2) NOT NULL,
  `platform` VARCHAR(20) NOT NULL,
  `image_url` VARCHAR(500) NOT NULL,
  `product_url` VARCHAR(500) NOT NULL,
  `description` VARCHAR(500) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `user_ai_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `provider_name` VARCHAR(50) NOT NULL DEFAULT 'dashscope',
  `base_url` VARCHAR(500) NOT NULL DEFAULT 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  `api_key_encrypted` TEXT DEFAULT NULL,
  `api_key_masked` VARCHAR(100) DEFAULT NULL,
  `vision_model` VARCHAR(100) NOT NULL DEFAULT 'qwen-vl-max',
  `chat_model` VARCHAR(100) NOT NULL DEFAULT 'qwen-plus',
  `image_model` VARCHAR(100) DEFAULT 'gpt-image-1',
  `model_reference_url` VARCHAR(500) DEFAULT NULL,
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_cfg_user_id` (`user_id`),
  CONSTRAINT `fk_ai_cfg_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
);

ALTER TABLE `wardrobe_item` ADD COLUMN IF NOT EXISTS `price` DECIMAL(10,2) DEFAULT NULL;
ALTER TABLE `outfit_plan` ADD COLUMN IF NOT EXISTS `collage_layout` TEXT DEFAULT NULL;
ALTER TABLE `outfit_plan` ADD COLUMN IF NOT EXISTS `try_on_image_url` VARCHAR(500) DEFAULT NULL;
ALTER TABLE `user_ai_config` ADD COLUMN IF NOT EXISTS `image_model` VARCHAR(100) DEFAULT 'gpt-image-1';
ALTER TABLE `user_ai_config` ADD COLUMN IF NOT EXISTS `model_reference_url` VARCHAR(500) DEFAULT NULL;
ALTER TABLE `user_preference` ADD COLUMN IF NOT EXISTS `body_shape` VARCHAR(40) DEFAULT 'H型直筒';
ALTER TABLE `user_preference` ADD COLUMN IF NOT EXISTS `color_season` VARCHAR(40) DEFAULT '中性百搭';
ALTER TABLE `user_preference` ADD COLUMN IF NOT EXISTS `height_cm` INT DEFAULT 165;

CREATE TABLE IF NOT EXISTS `inspo_like` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `look_id` VARCHAR(64) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inspo_like_user_look` (`user_id`, `look_id`),
  CONSTRAINT `fk_inspo_like_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS `try_on_job` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `plan_id` BIGINT NOT NULL,
  `plan_theme_title` VARCHAR(120) DEFAULT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
  `style_preset` VARCHAR(40) DEFAULT 'studio_white',
  `generation_mode` VARCHAR(30) DEFAULT 'auto',
  `use_reference_photo` TINYINT NOT NULL DEFAULT 1,
  `used_real_image_model` TINYINT NOT NULL DEFAULT 0,
  `used_reference_photo` TINYINT NOT NULL DEFAULT 0,
  `model_used` VARCHAR(100) DEFAULT NULL,
  `endpoint_used` VARCHAR(100) DEFAULT NULL,
  `prompt_used` TEXT DEFAULT NULL,
  `preview_image_url` VARCHAR(500) DEFAULT NULL,
  `notice` VARCHAR(500) DEFAULT NULL,
  `error_message` VARCHAR(500) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_tryon_job_user_created` (`user_id`, `created_at`),
  KEY `idx_tryon_job_plan` (`plan_id`),
  CONSTRAINT `fk_tryon_job_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_tryon_job_plan` FOREIGN KEY (`plan_id`) REFERENCES `outfit_plan` (`id`) ON DELETE CASCADE
);
