package com.wardrobe.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

public class InspoDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SimilarProductVo {
        private String id;
        private String brand;
        private String title;
        private BigDecimal price;
        private BigDecimal originalPrice;
        private String platform; // taobao | jd | tmall
        private String platformLabel;
        private String imageUrl;
        private String svgType;
        private String colorHex;
        private String buyUrl;
        private String taobaoUrl;
        private String jdUrl;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LookPieceVo {
        private String id;
        private String brand;
        private String name;
        private String category; // outerwear | top | bottom | shoes | accessory
        private String slotLabel;
        private String color;
        private String colorHex;
        private BigDecimal price;
        private String imageUrl;
        private String svgType;
        private String taobaoUrl;
        private String jdUrl;
        private List<SimilarProductVo> similarItems;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InspoLookVo {
        private String id;
        private String creatorName;
        private String creatorHandle;
        private String city;
        private String gender; // womens | mens
        private String title;
        private String styleTag;
        private String seasonTag;
        private String viewsText;
        private int viewsCount;
        private int rank;
        private int likesCount;
        private int commentsCount;
        private int restylesCount;
        private boolean liked;
        private int matchScore;
        private String matchReason;
        private String modelImageUrl;
        private String fallbackSvgType;
        private List<String> styleTags;
        private List<LookPieceVo> pieces;
        private List<String> sampleComments;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BannerVo {
        private String id;
        private String eyebrow;
        private String title;
        private String subtitle;
        private String ctaLabel;
        private String brandFilter;
        private String bgStyle;
        private String textTone; // dark | light
        private String imageUrl;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BrandCollectionVo {
        private String id;
        private String name;
        private String cnName;
        private String origin;
        private String tagline;
        private String priceBand;
        private String taobaoFlagshipUrl;
        private String jdFlagshipUrl;
        private List<String> lookIds;
        private String coverImageUrl;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeedResponse {
        private List<BannerVo> banners;
        private List<InspoLookVo> looks;
        private List<BrandCollectionVo> brands;
        private int likedCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LikeToggleVo {
        private String lookId;
        private boolean liked;
        private int likesCount;
    }
}
