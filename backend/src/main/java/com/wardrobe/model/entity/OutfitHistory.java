package com.wardrobe.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("outfit_history")
public class OutfitHistory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long planId;
    private String themeTitle;
    private LocalDate wearDate;
    private String sceneTag;
    private String weatherInfo;
    private Integer weatherTemp;
    private String weatherIcon;
    private String itemIds;
    private String aiReasoning;
    private Integer feedbackScore;
    private String notes;
    private LocalDateTime createdAt;
}
