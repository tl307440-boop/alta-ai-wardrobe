package com.wardrobe.model.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_ai_config")
public class UserAiConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String providerName;
    private String baseUrl;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String apiKeyEncrypted;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String apiKeyMasked;
    private String visionModel;
    private String chatModel;
    private String imageModel;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String modelReferenceUrl;
    private Integer enabled;
    private LocalDateTime updatedAt;
}
