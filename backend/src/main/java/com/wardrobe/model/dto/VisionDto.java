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

    /** Normalized bounding box (fractions of the source image, each in 0..1, origin = top-left). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BoundingBox {
        private double x;
        private double y;
        private double w;
        private double h;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DetectedItem {
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
        /** Null when the model returned no usable box for this item. */
        private BoundingBox bbox;
        /** Name of a highly similar piece already in the caller's wardrobe (null when none). */
        private String similarWardrobeName;
        private int similarity;
        private String aiRawResult;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemsDetectionVo {
        /** Stored full photo; null when no model is configured (nothing is written to disk then). */
        private String originalImageUrl;
        private List<DetectedItem> items;
        private boolean usedRealLlm;
        private String notice;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StoredItemImageVo {
        private String originalImageUrl;
        private String processedImageUrl;
        private boolean cutoutFallback;
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
