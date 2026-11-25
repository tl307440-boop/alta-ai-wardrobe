package com.wardrobe.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("outfit_plan")
public class OutfitPlan {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String themeTitle;
    private String triggerType;
    private String sceneTag;
    private String weatherSnapshot;
    private String userQuery;
    private String inspirationImageUrl;
    private String itemIds;
    private String missingItems;
    private String recommendedProductIds;
    private String aiReasoning;
    private BigDecimal matchScore;
    private String collageLayout;
    private String tryOnImageUrl;
    private Integer isFavorite;
    private LocalDateTime createdAt;
}
