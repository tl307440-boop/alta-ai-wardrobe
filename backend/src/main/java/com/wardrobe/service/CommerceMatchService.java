package com.wardrobe.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wardrobe.common.JsonUtils;
import com.wardrobe.mapper.MockProductMapper;
import com.wardrobe.model.dto.OutfitDto;
import com.wardrobe.model.entity.MockProduct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CommerceMatchService {
    private final MockProductMapper mockProductMapper;
    private final JsonUtils jsonUtils;

    public List<OutfitDto.ProductVo> listAll(String category, BigDecimal maxPrice) {
        LambdaQueryWrapper<MockProduct> qw = new LambdaQueryWrapper<>();
        if (category != null && !category.isBlank() && !"all".equalsIgnoreCase(category)) {
            qw.eq(MockProduct::getCategory, category.trim());
        }
        if (maxPrice != null) {
            qw.le(MockProduct::getPrice, maxPrice);
        }
        qw.orderByAsc(MockProduct::getPrice);
        return mockProductMapper.selectList(qw).stream()
                .map(this::toVo)
                .collect(Collectors.toList());
    }

    public List<OutfitDto.ProductVo> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        return mockProductMapper.selectBatchIds(ids).stream()
                .map(this::toVo)
                .collect(Collectors.toList());
    }

    public List<OutfitDto.ProductVo> matchProducts(String category, String keywords, String color, BigDecimal budgetLimit, int limit) {
        BigDecimal cap = budgetLimit != null ? budgetLimit : new BigDecimal("300");
        LambdaQueryWrapper<MockProduct> qw = new LambdaQueryWrapper<MockProduct>()
                .le(MockProduct::getPrice, cap);
        if (category != null && !category.isBlank()) {
            qw.eq(MockProduct::getCategory, category.trim());
        }
        List<MockProduct> candidates = mockProductMapper.selectList(qw);
        if (candidates.isEmpty() && category != null && !category.isBlank()) {
            // Secondary fallback across all categories within budget if requested category has no product under tight budget
            candidates = mockProductMapper.selectList(
                    new LambdaQueryWrapper<MockProduct>().le(MockProduct::getPrice, cap));
        }

        String kwLower = Objects.toString(keywords, "").toLowerCase();
        String colorLower = Objects.toString(color, "").toLowerCase();

        return candidates.stream()
                .sorted((a, b) -> Double.compare(
                        scoreProduct(b, category, kwLower, colorLower),
                        scoreProduct(a, category, kwLower, colorLower)))
                .limit(limit)
                .map(this::toVo)
                .collect(Collectors.toList());
    }

    private double scoreProduct(MockProduct p, String category, String kwLower, String colorLower) {
        double score = 0;
        if (category != null && category.equalsIgnoreCase(p.getCategory())) score += 50;
        if (!colorLower.isBlank() && p.getColor() != null && p.getColor().toLowerCase().contains(colorLower)) score += 20;
        String text = (Objects.toString(p.getTitle(), "") + " " + Objects.toString(p.getDescription(), "") + " " + Objects.toString(p.getStyleTags(), "")).toLowerCase();
        for (String token : kwLower.split("[\\s,，、]+")) {
            if (token.length() >= 2 && text.contains(token)) {
                score += 15;
            }
        }
        // Slight preference for better value inside budget
        score += Math.max(0, 10 - p.getPrice().doubleValue() / 50.0);
        return score;
    }

    public OutfitDto.ProductVo toVo(MockProduct p) {
        String label = switch (Objects.toString(p.getPlatform(), "").toLowerCase()) {
            case "taobao" -> "淘宝";
            case "jd" -> "京东";
            case "pdd" -> "拼多多";
            default -> "精选电商";
        };
        return OutfitDto.ProductVo.builder()
                .id(p.getId())
                .title(p.getTitle())
                .category(p.getCategory())
                .color(p.getColor())
                .colorHex(p.getColorHex() != null ? p.getColorHex() : "#DFD3C3")
                .svgType(p.getSvgType() != null ? p.getSvgType() : "trench")
                .styleTags(jsonUtils.toStringList(p.getStyleTags()))
                .price(p.getPrice())
                .platform(p.getPlatform())
                .platformLabel(label)
                .imageUrl(p.getImageUrl())
                .productUrl(p.getProductUrl())
                .description(p.getDescription())
                .build();
    }
}
