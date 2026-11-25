package com.wardrobe.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public class VisionDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemRecognitionVo {
        private boolean isClothing;
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
        private boolean cutoutFallback;
        private String aiRawResult;
        private double confidence;
        private boolean usedRealLlm;
        private String notice;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InspirationItemMatch {
        private String slotLabel;
        private String name;
        private String category;
        private String color;
        private String colorHex;
        private String svgType;
        private List<String> styleTags;
        private String keywords;
        private int posX;
        private int posY;
        private WardrobeDto.ItemVo matchedWardrobe;
        private int similarity;
        private List<OutfitDto.ProductVo> products;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InspirationAnalyzeVo {
        private String overallStyle;
        private String inspirationImageUrl;
        private List<InspirationItemMatch> items;
        private boolean usedRealLlm;
        private String notice;
    }
}
