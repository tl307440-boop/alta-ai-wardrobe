package com.wardrobe.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class OutfitDto {

    @Data
    public static class RecommendRequest {
        /** daily_weather | nl_query | anchor_item | inspiration_img */
        private String triggerType = "daily_weather";
        private String sceneTag;
        private String userQuery;
        private Long anchorItemId;
        private BigDecimal budgetLimit;
        private String refineInstruction;
        private Long basePlanId;
        /** Optional weather override from header/lookbook simulator */
        private Integer weatherTemp;
        private String weatherCondition;
        private String weatherIcon;
        private String weatherCity;
        /** Revive neglected/idle wardrobe pieces (wearCount <= 2) */
        private Boolean reviveIdle;
        /** Optional item IDs to penalize/avoid (used by dislike-and-replace) */
        private List<Long> excludeItemIds;
    }

    @Data
    public static class SwapSlotRequest {
        private String category;
        private List<Long> excludeItemIds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CollageNode {
        private String nodeKey;
        private String slotKey;
        private Long itemId;
        private Double x;        // percentage 0..100
        private Double y;        // percentage 0..100
        private Double scale;    // 0.35..2.2
        private Double rotation; // degrees -180..180
        @JsonProperty("zIndex")
        private Integer zIndex;
        private Boolean flipX;
        private String customName;
        private String customImageUrl;
        private String customSvgType;
        private String customColorHex;
        private String labelText;
    }

    @Data
    public static class SaveCollageLayoutRequest {
        private List<CollageNode> nodes;
    }

    @Data
    public static class TryOnRequest {
        private String stylePreset;       // studio_white | street_shanghai | magazine_editorial | window_light
        private String customPrompt;
        private Boolean useReferencePhoto = true;
        private String generationMode;    // auto | fast | chat_stream | hd
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TryOnResultVo {
        private Long planId;
        private String previewImageUrl;
        private boolean usedRealImageModel;
        private boolean usedReferencePhoto;
        private String modelUsed;
        private String endpointUsed;
        private String promptUsed;
        private String notice;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TryOnJobVo {
        private Long id;
        private Long planId;
        private String planThemeTitle;
        private String status; // QUEUED | PROCESSING | SUCCEEDED | FAILED
        private String stylePreset;
        private String generationMode;
        private boolean useReferencePhoto;
        private boolean usedRealImageModel;
        private boolean usedReferencePhoto;
        private String modelUsed;
        private String endpointUsed;
        private String promptUsed;
        private String previewImageUrl;
        private String notice;
        private String errorMessage;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class RecordHistoryRequest {
        private Long planId;
        private String themeTitle;
        private LocalDate wearDate;
        private String sceneTag;
        private String weatherInfo;
        private Integer weatherTemp;
        private String weatherIcon;
        private List<Long> itemIds;
        private String aiReasoning;
        private Integer feedbackScore = 5;
        private String notes;
    }

    @Data
    public static class UpdateHistoryRequest {
        private Integer feedbackScore;
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MissingSlotVo {
        private String category;
        private String name;
        private String color;
        private String colorHex;
        private String svgType;
        private String keywords;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanSlotVo {
        private String slotKey;
        private String slotLabel;
        private boolean isMissing;
        private boolean isAnchor;
        private WardrobeDto.ItemVo item;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductVo {
        private Long id;
        private String title;
        private String category;
        private String color;
        private String colorHex;
        private String svgType;
        private List<String> styleTags;
        private BigDecimal price;
        private String platform;
        private String platformLabel;
        private String imageUrl;
        private String productUrl;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanVo {
        private Long id;
        private String themeTitle;
        private String triggerType;
        private String sceneTag;
        private WeatherVo weatherSnapshot;
        private String userQuery;
        private List<PlanSlotVo> slots;
        private List<MissingSlotVo> missingItems;
        private List<ProductVo> recommendedProducts;
        private String aiReasoning;
        private BigDecimal matchScore;
        private List<CollageNode> collageLayout;
        private String tryOnImageUrl;
        private boolean isFavorite;
        private boolean usedRealLlm;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoryVo {
        private Long id;
        private Long planId;
        private String themeTitle;
        private LocalDate wearDate;
        private String sceneTag;
        private String weatherInfo;
        private Integer weatherTemp;
        private String weatherIcon;
        private List<Long> itemIds;
        private List<WardrobeDto.ItemVo> items;
        private List<String> palette;
        private String aiReasoning;
        private Integer feedbackScore;
        private String notes;
    }
}
