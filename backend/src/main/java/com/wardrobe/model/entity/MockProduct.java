package com.wardrobe.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("mock_product")
public class MockProduct {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String category;
    private String color;
    private String colorHex;
    private String svgType;
    private String styleTags;
    private BigDecimal price;
    private String platform;
    private String imageUrl;
    private String productUrl;
    private String description;
    private LocalDateTime createdAt;
}
