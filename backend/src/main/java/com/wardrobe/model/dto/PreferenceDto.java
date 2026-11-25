package com.wardrobe.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class PreferenceDto {

    @Data
    public static class UpdateRequest {
        private String defaultCity;
        private List<String> favoriteStyles;
        private List<String> favoriteColors;
        private List<String> dislikedColors;
        private List<String> preferredScenes;
        private BigDecimal budgetLimit;
        private String bodyShape;
        private String colorSeason;
        private Integer heightCm;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StyleAffinityItem {
        private String name;
        private int score;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryStatItem {
        private String key;
        private String label;
        private long count;
        private int percent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColorStatItem {
        private String color;
        private String hex;
        private long count;
        private int percent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColorPairItem {
        private String colorA;
        private String hexA;
        private String colorB;
        private String hexB;
        private int count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MirrorPieceItem {
        private Long id;
        private String name;
        private String category;
        private String color;
        private String colorHex;
        private int wearCount;
        private BigDecimal price;
        private BigDecimal costPerWear;
        private String imageUrl;
        private String svgType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PreferenceVo {
        private String defaultCity;
        private List<String> favoriteStyles;
        private List<String> favoriteColors;
        private List<String> dislikedColors;
        private List<String> preferredScenes;
        private BigDecimal budgetLimit;
        private String bodyShape;
        private String colorSeason;
        private Integer heightCm;
        private String bodyShapeAdvice;
        private String colorSeasonAdvice;
        private List<String> seasonRecommendedColors;
        private Map<String, Double> styleWeights;
        private List<CategoryStatItem> categoryStats;
        private List<StyleAffinityItem> styleAffinity;
        private List<ColorStatItem> colorStats;
        private List<ColorPairItem> colorPairFingerprints;
        private List<MirrorPieceItem> coreStaples;
        private List<MirrorPieceItem> neglectedPieces;
        private int idleRate;
        private BigDecimal totalWardrobeValue;
        private BigDecimal avgCostPerWear;
        private int pricedItemCount;
        private String bestValueItemName;
        private BigDecimal bestValueCpw;
        private String highestCpwItemName;
        private BigDecimal highestCpw;
    }
}
