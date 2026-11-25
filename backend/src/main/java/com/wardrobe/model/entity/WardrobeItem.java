package com.wardrobe.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("wardrobe_item")
public class WardrobeItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String category;
    private String subCategory;
    private String color;
    private String colorHex;
    private String season;
    private Integer thickness;
    private String styleTags;
    private String material;
    private String svgType;
    private String originalImageUrl;
    private String processedImageUrl;
    private String sourceType;
    private String aiRawResult;
    private Integer isManuallyCorrected;
    private BigDecimal price;
    private Integer wearCount;
    private LocalDate lastWornDate;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
