package com.wardrobe.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("user_preference")
public class UserPreference {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String defaultCity;
    private String favoriteStyles;
    private String favoriteColors;
    private String dislikedColors;
    private String preferredScenes;
    private BigDecimal budgetLimit;
    private String bodyShape;
    private String colorSeason;
    private Integer heightCm;
    private String styleWeights;
    private LocalDateTime updatedAt;
}
