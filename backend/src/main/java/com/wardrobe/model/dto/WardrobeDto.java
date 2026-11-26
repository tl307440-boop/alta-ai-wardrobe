package com.wardrobe.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class WardrobeDto {

    @Data
    public static class CreateItemRequest {
        @NotBlank(message = "单品名称不能为空")
        private String name;

        @NotBlank(message = "一级品类不能为空")
        private String category;

        private String subCategory;

        @NotBlank(message = "主色调不能为空")
        private String color;

        private String colorHex = "#EFECE6";
        private List<String> seasons;
        private Integer thickness = 2;
        private List<String> styleTags;
        private String material;
        private String svgType;
        private String originalImageUrl;
        private String processedImageUrl;
        private String sourceType = "photo";
        private String aiRawResult;
        private Boolean isManuallyCorrected = false;
        private BigDecimal price;
        private String remark;
    }

    @Data
    public static class UpdateItemRequest {
        private String name;
        private String category;
        private String subCategory;
        private String color;
        private String colorHex;
        private List<String> seasons;
        private Integer thickness;
        private List<String> styleTags;
        private String material;
        private BigDecimal price;
        private String remark;
        private String processedImageUrl;
        private String originalImageUrl;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemVo {
        private Long id;
        private Long userId;
        private String name;
        private String category;
        private String subCategory;
        private String color;
        private String colorHex;
        private List<String> seasons;
        private Integer thickness;
        private List<String> styleTags;
        private String material;
        private String svgType;
        private String originalImageUrl;
        private String processedImageUrl;
        private String sourceType;
        private Boolean isManuallyCorrected;
        private BigDecimal price;
        private BigDecimal costPerWear;
        private Integer wearCount;
        private LocalDate lastWornDate;
        private String remark;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemDetailVo {
        private ItemVo item;
        private List<OutfitDto.HistoryVo> relatedHistory;
    }
}
