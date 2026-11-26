package com.wardrobe.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wardrobe.common.JsonUtils;
import com.wardrobe.mapper.*;
import com.wardrobe.model.entity.*;
import com.wardrobe.security.PasswordUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeedDataInitializer implements ApplicationRunner {
    private final SysUserMapper userMapper;
    private final UserPreferenceMapper preferenceMapper;
    private final WardrobeItemMapper wardrobeItemMapper;
    private final OutfitPlanMapper outfitPlanMapper;
    private final OutfitHistoryMapper outfitHistoryMapper;
    private final MockProductMapper mockProductMapper;
    private final PasswordUtil passwordUtil;
    private final JsonUtils jsonUtils;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        ensureSchemaMigrations();
        seedProductsIfEmpty();
        upgradeLegacyDemoProductUrls();
        seedDemoUserIfMissing();
        backfillDemoItemPrices();
        upgradeLegacySvgAssetsToAltaCutouts();
    }

    /**
     * Idempotent startup column guard for existing MySQL 8.0 / H2 volumes created before new columns existed.
     */
    private void ensureSchemaMigrations() {
        try {
            jdbcTemplate.query("SELECT ootd_photo_url FROM outfit_history WHERE 1 = 0", rs -> null);
        } catch (Exception missingCol) {
            try {
                jdbcTemplate.execute("ALTER TABLE outfit_history ADD COLUMN ootd_photo_url VARCHAR(500) DEFAULT NULL");
                log.info("Applied idempotent schema migration: added outfit_history.ootd_photo_url");
            } catch (Exception e) {
                log.debug("Skipped outfit_history.ootd_photo_url migration: {}", e.getMessage());
            }
        }
        try {
            jdbcTemplate.query("SELECT visual_report FROM try_on_job WHERE 1 = 0", rs -> null);
        } catch (Exception missingCol) {
            try {
                jdbcTemplate.execute("ALTER TABLE try_on_job ADD COLUMN visual_report TEXT DEFAULT NULL");
                log.info("Applied idempotent schema migration: added try_on_job.visual_report");
            } catch (Exception e) {
                log.debug("Skipped try_on_job.visual_report migration: {}", e.getMessage());
            }
        }
        ensureColumn("user_ai_config", "vton_enabled", "TINYINT NOT NULL DEFAULT 0");
        ensureColumn("user_ai_config", "vton_provider", "VARCHAR(50) DEFAULT 'dashscope'");
        ensureColumn("user_ai_config", "vton_model", "VARCHAR(100) DEFAULT 'aitryon-plus'");
        ensureColumn("user_ai_config", "vton_api_key_encrypted", "TEXT DEFAULT NULL");
        ensureColumn("user_ai_config", "vton_api_key_masked", "VARCHAR(100) DEFAULT NULL");
        ensureColumn("user_ai_config", "vton_public_base_url", "VARCHAR(500) DEFAULT NULL");
    }

    private void ensureColumn(String table, String column, String definition) {
        try {
            jdbcTemplate.query("SELECT " + column + " FROM " + table + " WHERE 1 = 0", rs -> null);
        } catch (Exception missingCol) {
            try {
                jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
                log.info("Applied idempotent schema migration: added {}.{}", table, column);
            } catch (Exception e) {
                log.debug("Skipped {}.{} migration: {}", table, column, e.getMessage());
            }
        }
    }

    /**
     * Maps garment svgType / Chinese name / color to real studio-segmented transparent Alta cutouts
     * cached locally under frontend/public/alta/pieces/*.webp.
     */
    public static String resolveAltaPieceCutout(String svgType, String name, String color) {
        String n = Objects.toString(name, "");
        String c = Objects.toString(color, "");
        String s = Objects.toString(svgType, "").toLowerCase(Locale.ROOT);

        // Exact title matches for demo wardrobe & mock commerce catalog
        if (n.contains("麻花羊毛针织开衫")) return "/alta/pieces/cream-polo-knit.webp";
        if (n.contains("针织开衫") || "cardigan".equals(s)) return "/alta/pieces/cream-cable-sweater.webp";
        if (n.contains("条纹") || "striped_shirt".equals(s)) return "/alta/pieces/blue-chambray-shirt.webp";
        if (n.contains("白T") || n.contains("白衬衫") || ("tshirt".equals(s) && (c.contains("白") || n.contains("白")))) {
            return "/alta/pieces/white-shirt.webp";
        }
        if (n.contains("暖咖") && n.contains("背心")) return "/alta/pieces/brown-cable-sweater.webp";
        if (n.contains("针织背心") || "knit_vest".equals(s)) {
            return c.contains("黑") ? "/alta/pieces/black-turtleneck.webp" : "/alta/pieces/brown-knit-sweater.webp";
        }
        if (n.contains("麂皮") || n.contains("工装夹克") || "suede_jacket".equals(s)) {
            return "/alta/pieces/brown-bomber-jacket.webp";
        }
        if (n.contains("长款防风风衣")) return "/alta/pieces/brown-trench.webp";
        if (n.contains("风衣") || "trench".equals(s)) return "/alta/pieces/beige-belted-coat.webp";
        if (n.contains("西装") && !n.contains("裤")) {
            return c.contains("黑") ? "/alta/pieces/black-blazer.webp" : "/alta/pieces/cream-blazer.webp";
        }
        if ("blazer".equals(s)) {
            return c.contains("黑") ? "/alta/pieces/black-blazer.webp" : "/alta/pieces/cream-blazer.webp";
        }
        if (n.contains("牛仔裤") || "jeans".equals(s)) {
            return c.contains("黑") ? "/alta/pieces/black-wide-jeans.webp" : "/alta/pieces/dark-blue-jeans.webp";
        }
        if (n.contains("浅燕麦") && n.contains("阔腿")) return "/alta/pieces/cream-wide-pants.webp";
        if (n.contains("阔腿裤") || n.contains("西裤") || "trousers".equals(s)) {
            return (c.contains("燕麦") || c.contains("米") || c.contains("白"))
                    ? "/alta/pieces/cream-wide-pants.webp" : "/alta/pieces/black-pleated-pants.webp";
        }
        if (n.contains("焦糖格纹") && n.contains("裙")) return "/alta/pieces/brown-tulip-skirt.webp";
        if (n.contains("半身裙") || n.contains("百褶") || "pleated_skirt".equals(s)) {
            return c.contains("黑") ? "/alta/pieces/black-maxi-skirt.webp" : "/alta/pieces/beige-pleated-skirt.webp";
        }
        if (n.contains("厚底") && n.contains("乐福")) return "/alta/pieces/black-loafers.webp";
        if (n.contains("乐福") || "loafers".equals(s)) return "/alta/pieces/tan-oxfords.webp";
        if (n.contains("翻毛拼接德训")) return "/alta/pieces/veja-suede-sneakers.webp";
        if (n.contains("德训") || "sneakers".equals(s)) return "/alta/pieces/veja-white-sneakers.webp";
        if (n.contains("托特") || "tote_bag".equals(s)) {
            return c.contains("黑") ? "/alta/pieces/black-tote.webp" : "/alta/pieces/cream-tote.webp";
        }
        if (n.contains("马鞍") || n.contains("腋下包") || "crossbody_bag".equals(s)) {
            return n.contains("法式") ? "/alta/pieces/taupe-crescent-bag.webp" : "/alta/pieces/brown-shoulder-bag.webp";
        }
        return "/alta/pieces/white-shirt.webp";
    }

    private void upgradeLegacySvgAssetsToAltaCutouts() {
        List<MockProduct> allProducts = mockProductMapper.selectList(null);
        int upgradedProducts = 0;
        for (MockProduct p : allProducts) {
            String cur = p.getImageUrl();
            if (cur == null || cur.startsWith("svg://") || cur.contains("unsplash.com")) {
                p.setImageUrl(resolveAltaPieceCutout(p.getSvgType(), p.getTitle(), p.getColor()));
                mockProductMapper.updateById(p);
                upgradedProducts++;
            }
        }

        List<WardrobeItem> allItems = wardrobeItemMapper.selectList(null);
        int upgradedItems = 0;
        for (WardrobeItem it : allItems) {
            String cur = it.getProcessedImageUrl();
            // Only upgrade legacy vector placeholders; never overwrite real user-uploaded /uploads/... photos
            if (cur == null || cur.startsWith("svg://")) {
                it.setProcessedImageUrl(resolveAltaPieceCutout(it.getSvgType(), it.getName(), it.getColor()));
                wardrobeItemMapper.updateById(it);
                upgradedItems++;
            }
        }

        List<OutfitPlan> allPlans = outfitPlanMapper.selectList(null);
        for (OutfitPlan plan : allPlans) {
            if (plan.getTryOnImageUrl() != null && plan.getTryOnImageUrl().contains("unsplash.com")) {
                plan.setTryOnImageUrl("/alta/looks/w2.webp");
                outfitPlanMapper.updateById(plan);
            }
        }

        if (upgradedProducts > 0 || upgradedItems > 0) {
            log.info("Upgraded {} mock products and {} placeholder wardrobe items to real Alta studio cutouts.",
                    upgradedProducts, upgradedItems);
        }
    }

    private void upgradeLegacyDemoProductUrls() {
        List<MockProduct> all = mockProductMapper.selectList(null);
        for (MockProduct p : all) {
            if (p.getProductUrl() != null && p.getProductUrl().contains("/demo/")) {
                String kw = java.net.URLEncoder.encode(p.getTitle(), java.nio.charset.StandardCharsets.UTF_8);
                if ("jd".equalsIgnoreCase(p.getPlatform())) {
                    p.setProductUrl("https://search.jd.com/Search?keyword=" + kw);
                } else {
                    p.setPlatform("taobao");
                    p.setProductUrl("https://s.taobao.com/search?q=" + kw);
                }
                mockProductMapper.updateById(p);
            }
        }
    }

    private void backfillDemoItemPrices() {
        SysUser demo = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, "demo_user"));
        if (demo == null) return;
        Map<String, String> defaultPrices = Map.ofEntries(
                Map.entry("米白针织开衫", "299.00"),
                Map.entry("基础重磅白T", "129.00"),
                Map.entry("炭黑V领针织背心", "229.00"),
                Map.entry("深蓝直筒牛仔裤", "369.00"),
                Map.entry("黑色垂感西装阔腿裤", "329.00"),
                Map.entry("卡其高腰百褶半身裙", "259.00"),
                Map.entry("焦糖棕皮质乐福鞋", "499.00"),
                Map.entry("复古米灰德训鞋", "459.00"),
                Map.entry("燕麦帆布拼皮托特包", "389.00"),
                Map.entry("焦糖复古马鞍腋下包", "529.00"),
                Map.entry("浅燕麦微廓形西装", "599.00")
        );
        List<WardrobeItem> items = wardrobeItemMapper.selectList(
                new LambdaQueryWrapper<WardrobeItem>().eq(WardrobeItem::getUserId, demo.getId()));
        for (WardrobeItem it : items) {
            if (it.getPrice() == null && defaultPrices.containsKey(it.getName())) {
                it.setPrice(new BigDecimal(defaultPrices.get(it.getName())));
                wardrobeItemMapper.updateById(it);
            }
        }
    }

    private void seedProductsIfEmpty() {
        Long count = mockProductMapper.selectCount(null);
        if (count != null && count > 0) return;

        LocalDateTime now = LocalDateTime.now();
        List<MockProduct> products = List.of(
                buildProduct("经典英伦双排扣米色短款风衣", "outerwear", "燕麦", "#DFD3C3", "trench",
                        List.of("极简通勤", "英伦风"), "269.00", "taobao", "https://s.taobao.com/search?q=%E7%BB%8F%E5%85%B8%E8%8B%B1%E4%BC%A6%E5%8F%8C%E6%8E%92%E6%89%A3%E7%B1%B3%E8%89%B2%E7%9F%AD%E6%AC%BE%E9%A3%8E%E8%A1%A3",
                        "短款显高，完美适配微凉 18℃ 通勤叠穿，与牛仔裤及白T极搭", now),
                buildProduct("复古美拉德翻毛麂皮工装夹克", "outerwear", "焦糖棕", "#8A5230", "suede_jacket",
                        List.of("美拉德复古", "工装休闲"), "289.00", "taobao", "https://s.taobao.com/search?q=%E5%A4%8D%E5%8F%A4%E7%BE%8E%E6%8B%89%E5%BE%B7%E7%BF%BB%E6%AF%9B%E9%BA%82%E7%9A%AE%E5%B7%A5%E8%A3%85%E5%A4%B9%E5%85%8B",
                        "秋日美拉德核心外套，轻松点亮基础款白T+深蓝牛仔", now),
                buildProduct("极简法式条纹海魂衫长袖打底", "top", "牛仔蓝", "#3B5372", "striped_shirt",
                        List.of("法式松弛", "Clean Fit"), "129.00", "taobao", "https://s.taobao.com/search?q=%E6%9E%81%E7%AE%80%E6%B3%95%E5%BC%8F%E6%9D%A1%E7%BA%B9%E6%B5%B7%E9%AD%82%E8%A1%AB%E9%95%BF%E8%A2%96%E6%89%93%E5%BA%95",
                        "高性价比内搭平替，增添法式松弛感", now),
                buildProduct("轻奢垂感卡其长款防风风衣", "outerwear", "卡其", "#C7B299", "trench",
                        List.of("极简通勤", "大女主"), "459.00", "jd", "https://search.jd.com/Search?keyword=%E8%BD%BB%E5%A5%A2%E5%9E%82%E6%84%9F%E5%8D%A1%E5%85%B6%E9%95%BF%E6%AC%BE%E9%98%B2%E9%A3%8E%E9%A3%8E%E8%A1%A3",
                        "防风抗皱高支斜纹面料，适合较高预算职场进阶", now),
                buildProduct("羊绒质感暖咖针织保暖打底背心", "top", "焦糖棕", "#8A5230", "knit_vest",
                        List.of("美拉德复古", "学院风"), "189.00", "jd", "https://search.jd.com/Search?keyword=%E7%BE%8A%E7%BB%92%E8%B4%A8%E6%84%9F%E6%9A%96%E5%92%96%E9%92%88%E7%BB%87%E4%BF%9D%E6%9A%96%E6%89%93%E5%BA%95%E8%83%8C%E5%BF%83",
                        "9℃ 降温内搭优选，柔软锁温不显臃肿", now),
                buildProduct("复古厚底深棕漆皮乐福鞋平替", "shoes", "焦糖棕", "#8A5230", "loafers",
                        List.of("极简通勤", "学院风"), "178.00", "taobao", "https://s.taobao.com/search?q=%E5%A4%8D%E5%8F%A4%E5%8E%9A%E5%BA%95%E6%B7%B1%E6%A3%95%E6%BC%86%E7%9A%AE%E4%B9%90%E7%A6%8F%E9%9E%8B",
                        "200元内质感通勤鞋履，软底不磨脚", now),
                buildProduct("哑光软皮大容量极简通勤托特包", "accessory", "炭黑", "#262628", "tote_bag",
                        List.of("极简通勤", "干练面试"), "158.00", "taobao", "https://s.taobao.com/search?q=%E5%93%91%E5%85%89%E8%BD%AF%E7%9A%AE%E5%A4%A7%E5%AE%B9%E9%87%8F%E6%9E%81%E7%AE%80%E9%80%9A%E5%8B%A4%E6%89%98%E7%89%B9%E5%8C%85",
                        "可装14寸笔记本，与黑西装裤呼应显高级", now),
                buildProduct("极简浅燕麦垂感高腰阔腿西裤", "bottom", "燕麦", "#DFD3C3", "trousers",
                        List.of("极简通勤", "温柔韩系"), "169.00", "taobao", "https://s.taobao.com/search?q=%E6%9E%81%E7%AE%80%E6%B5%85%E7%87%95%E9%BA%A6%E5%9E%82%E6%84%9F%E9%AB%98%E8%85%B0%E9%98%94%E8%85%BF%E8%A5%BF%E8%A3%A4",
                        "纵向显腿长版型，浅燕麦色提亮秋冬季整体氛围", now),
                buildProduct("学院复古焦糖格纹百褶半身裙", "bottom", "卡其", "#C7B299", "pleated_skirt",
                        List.of("学院风", "温柔韩系", "约会"), "139.00", "taobao", "https://s.taobao.com/search?q=%E5%AD%A6%E9%99%A2%E5%A4%8D%E5%8F%A4%E7%84%A6%E7%B3%96%E6%A0%BC%E7%BA%B9%E7%99%BE%E8%A4%B6%E5%8D%8A%E8%BA%AB%E8%A3%99",
                        "显瘦高腰A字廓形，约会与周末出街都很出片", now),
                buildProduct("日系软糯燕麦麻花羊毛针织开衫", "outerwear", "米白", "#F3EDE2", "cardigan",
                        List.of("温柔韩系", "日常休闲"), "198.00", "jd", "https://search.jd.com/Search?keyword=%E6%97%A5%E7%B3%BB%E8%BD%AF%E7%B3%AF%E7%87%95%E9%BA%A6%E9%BA%BB%E8%8A%B1%E7%BE%8A%E6%AF%9B%E9%92%88%E7%BB%87%E5%BC%80%E8%A1%AB",
                        "微凉换季叠穿神器，轻盈亲肤且极具温柔画廊质感", now),
                buildProduct("Clean Fit 复古翻毛拼接德训鞋", "shoes", "米白", "#F3EDE2", "sneakers",
                        List.of("Clean Fit", "日常休闲"), "219.00", "taobao", "https://s.taobao.com/search?q=Clean%20Fit%20%E5%A4%8D%E5%8F%A4%E7%BF%BB%E6%AF%9B%E6%8B%BC%E6%8E%A5%E5%BE%B7%E8%AE%AD%E9%9E%8B",
                        "生胶防滑大底，暴走一万步依旧舒适百搭", now),
                buildProduct("法式复古焦糖牛皮马鞍腋下包", "accessory", "焦糖棕", "#8A5230", "crossbody_bag",
                        List.of("法式松弛", "美拉德复古", "约会"), "188.00", "jd", "https://search.jd.com/Search?keyword=%E6%B3%95%E5%BC%8F%E5%A4%8D%E5%8F%A4%E7%84%A6%E7%B3%96%E7%89%9B%E7%9A%AE%E9%A9%AC%E9%9E%8D%E8%85%8B%E4%B8%8B%E5%8C%85",
                        "低饱和焦糖金属扣设计，点亮简约基础款全身造型", now)
        );
        products.forEach(mockProductMapper::insert);
        log.info("Seeded {} mock e-commerce products.", products.size());
    }

    private void seedDemoUserIfMissing() {
        SysUser existing = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, "demo_user"));
        if (existing != null) return;

        LocalDateTime now = LocalDateTime.now();
        SysUser demo = new SysUser();
        demo.setUsername("demo_user");
        demo.setPassword(passwordUtil.encode("Wardrobe2026!"));
        demo.setNickname("林小满");
        demo.setGender(2);
        demo.setEmail("demo@aiwardrobe.local");
        demo.setStatus(1);
        demo.setCreatedAt(now);
        demo.setUpdatedAt(now);
        userMapper.insert(demo);

        Long uid = demo.getId();

        UserPreference pref = new UserPreference();
        pref.setUserId(uid);
        pref.setDefaultCity("武汉");
        pref.setFavoriteStyles(jsonUtils.toJson(List.of("极简通勤", "温柔韩系", "Clean Fit")));
        pref.setFavoriteColors(jsonUtils.toJson(List.of("米白", "燕麦", "牛仔蓝", "焦糖棕")));
        pref.setDislikedColors(jsonUtils.toJson(List.of("荧光绿")));
        pref.setPreferredScenes(jsonUtils.toJson(List.of("职场通勤", "周末约会", "互联网面试")));
        pref.setBudgetLimit(new BigDecimal("300.00"));
        pref.setBodyShape("H型直筒");
        pref.setColorSeason("中性百搭");
        pref.setHeightCm(165);
        pref.setStyleWeights(jsonUtils.toJson(Map.of("极简通勤", 0.92, "温柔韩系", 0.85, "Clean Fit", 0.78)));
        pref.setUpdatedAt(now);
        preferenceMapper.insert(pref);

        List<WardrobeItem> items = List.of(
                buildItem(uid, "米白针织开衫", "outerwear", "针织开衫", "米白", "#F3EDE2", "spring,autumn", 2,
                        List.of("温柔韩系", "极简通勤", "针织"), "棉羊毛混纺", "cardigan", "photo", "299.00", 8, now),
                buildItem(uid, "基础重磅白T", "top", "纯棉衬衫T恤", "纯白", "#FFFFFF", "spring,summer,autumn", 1,
                        List.of("Clean Fit", "极简通勤", "百搭"), "260g精梳纯棉", "tshirt", "screenshot", "129.00", 14, now),
                buildItem(uid, "炭黑V领针织背心", "top", "针织打底", "炭黑", "#262628", "spring,autumn,winter", 2,
                        List.of("学院风", "极简通勤", "针织"), "细针羊毛", "knit_vest", "photo", "229.00", 6, now),
                buildItem(uid, "深蓝直筒牛仔裤", "bottom", "直筒牛仔裤", "牛仔蓝", "#3B5372", "spring,summer,autumn,winter", 2,
                        List.of("Clean Fit", "日常休闲", "百搭"), "全棉丹宁", "jeans", "screenshot", "369.00", 12, now),
                buildItem(uid, "黑色垂感西装阔腿裤", "bottom", "西装阔腿裤", "炭黑", "#262628", "spring,autumn,winter", 2,
                        List.of("极简通勤", "干练面试"), "TR抗皱斜纹", "trousers", "photo", "329.00", 9, now),
                buildItem(uid, "卡其高腰百褶半身裙", "bottom", "百褶半身裙", "卡其", "#C7B299", "spring,summer,autumn", 1,
                        List.of("温柔韩系", "学院风", "约会"), "聚酯纤维混纺", "pleated_skirt", "photo", "259.00", 5, now),
                buildItem(uid, "焦糖棕皮质乐福鞋", "shoes", "乐福牛津鞋", "焦糖棕", "#8A5230", "spring,autumn,winter", 2,
                        List.of("极简通勤", "美拉德复古", "学院风"), "头层牛皮", "loafers", "screenshot", "499.00", 11, now),
                buildItem(uid, "复古米灰德训鞋", "shoes", "德训鞋", "燕麦", "#DFD3C3", "spring,summer,autumn,winter", 2,
                        List.of("Clean Fit", "日常休闲"), "翻毛皮拼接", "sneakers", "photo", "459.00", 10, now),
                buildItem(uid, "燕麦帆布拼皮托特包", "accessory", "托特包", "燕麦", "#DFD3C3", "spring,summer,autumn,winter", 2,
                        List.of("极简通勤", "温柔韩系"), "荔枝纹牛皮", "tote_bag", "photo", "389.00", 15, now),
                buildItem(uid, "焦糖复古马鞍腋下包", "accessory", "腋下包", "焦糖棕", "#8A5230", "spring,autumn,winter", 2,
                        List.of("美拉德复古", "约会"), "植鞣牛皮", "crossbody_bag", "photo", "529.00", 2, now),
                buildItem(uid, "浅燕麦微廓形西装", "outerwear", "西装外套", "燕麦", "#DFD3C3", "spring,autumn", 2,
                        List.of("极简通勤", "干练面试"), "精纺混纺", "blazer", "photo", "599.00", 7, now)
        );
        items.forEach(wardrobeItemMapper::insert);

        Long cardiganId = items.get(0).getId();
        Long teeId = items.get(1).getId();
        Long vestId = items.get(2).getId();
        Long jeansId = items.get(3).getId();
        Long trousersId = items.get(4).getId();
        Long skirtId = items.get(5).getId();
        Long loafersId = items.get(6).getId();
        Long sneakersId = items.get(7).getId();
        Long toteId = items.get(8).getId();
        Long saddleId = items.get(9).getId();
        Long blazerId = items.get(10).getId();

        OutfitPlan fav1 = new OutfitPlan();
        fav1.setUserId(uid);
        fav1.setThemeTitle("温柔通勤 · 轻松出门");
        fav1.setTriggerType("daily_weather");
        fav1.setSceneTag("微凉 18℃ · 职场通勤");
        fav1.setWeatherSnapshot(jsonUtils.toJson(Map.of("city", "武汉", "temp", 18, "condition", "多云", "icon", "cloud-sun", "tip", "适宜叠穿")));
        fav1.setItemIds(jsonUtils.toJson(List.of(teeId, jeansId, loafersId, toteId)));
        fav1.setMissingItems(jsonUtils.toJson(List.of(Map.of(
                "category", "outerwear", "name", "米色短款双排扣风衣", "color", "燕麦",
                "colorHex", "#DFD3C3", "svgType", "trench", "keywords", "米色 短款 风衣",
                "reason", "当前 18℃ 微凉多云，外搭短款风衣提升腰线并抵御早晚微风"
        ))));
        fav1.setAiReasoning("以衣橱已有的纯白打底与深蓝直筒牛仔裤构建利落基底，焦糖棕乐福鞋与燕麦托特包构成大地色系呼应；若补齐一件米色短款风衣，即可轻松拿捏微凉 18℃ 职场松弛感。");
        fav1.setMatchScore(new BigDecimal("96.00"));
        fav1.setTryOnImageUrl("/alta/looks/w8.webp");
        fav1.setIsFavorite(1);
        fav1.setCreatedAt(now.minusDays(2));
        outfitPlanMapper.insert(fav1);

        OutfitPlan fav2 = new OutfitPlan();
        fav2.setUserId(uid);
        fav2.setThemeTitle("利落松弛 · 互联网面试");
        fav2.setTriggerType("nl_query");
        fav2.setSceneTag("互联网面试");
        fav2.setUserQuery("明天去互联网公司面试，想要精神但别太死板的搭配");
        fav2.setItemIds(jsonUtils.toJson(List.of(blazerId, teeId, trousersId, loafersId, toteId)));
        fav2.setAiReasoning("浅燕麦微廓形西装打破传统黑西装的刻板感，内搭纯白打底与黑色垂感西装阔腿裤，配合焦糖乐福鞋呈现干净克制的专业气场。");
        fav2.setMatchScore(new BigDecimal("95.00"));
        fav2.setTryOnImageUrl("/alta/looks/w2.webp");
        fav2.setIsFavorite(1);
        fav2.setCreatedAt(now.minusDays(4));
        outfitPlanMapper.insert(fav2);

        OutfitPlan fav3 = new OutfitPlan();
        fav3.setUserId(uid);
        fav3.setThemeTitle("韩系温柔 · 周末约会");
        fav3.setTriggerType("nl_query");
        fav3.setSceneTag("约会温柔风");
        fav3.setItemIds(jsonUtils.toJson(List.of(cardiganId, teeId, skirtId, loafersId, saddleId)));
        fav3.setAiReasoning("米白针织开衫与卡其高腰百褶半身裙形成柔和燕麦顺色，焦糖复古马鞍腋下包与乐福鞋首尾呼应，自带温柔约会氛围滤镜。");
        fav3.setMatchScore(new BigDecimal("94.00"));
        fav3.setTryOnImageUrl("/alta/looks/w5.webp");
        fav3.setIsFavorite(1);
        fav3.setCreatedAt(now.minusDays(6));
        outfitPlanMapper.insert(fav3);

        LocalDate baseMonth = LocalDate.now().withDayOfMonth(1);
        List<OutfitHistory> histories = List.of(
                buildHistory(uid, fav1.getId(), "温柔通勤 · 轻松出门", baseMonth.withDayOfMonth(5), "职场通勤", "多云", 19, "cloud-sun",
                        List.of(cardiganId, teeId, jeansId, loafersId, toteId),
                        "米白针织开衫叠加纯白打底，下身以深蓝直筒牛仔裤平衡温柔与利落，焦糖乐福鞋与托特包构成大地色系呼应。",
                        5, "早晚温差刚刚好，在办公室脱掉开衫也很清爽。", now),
                buildHistory(uid, fav3.getId(), "秋日韩系 · 燕麦百褶", baseMonth.withDayOfMonth(11), "周末约会", "晴朗", 22, "sun",
                        List.of(cardiganId, teeId, skirtId, loafersId, saddleId),
                        "全身采用米白-卡其-焦糖的渐进同色系顺色法则，搭配马鞍腋下包勾勒温柔约会氛围。",
                        5, "拍照非常出片，朋友问了半身裙链接！", now),
                buildHistory(uid, fav2.getId(), "利落松弛 · 面试加分", baseMonth.withDayOfMonth(16), "互联网面试", "小雨", 13, "cloud-rain",
                        List.of(blazerId, teeId, trousersId, loafersId, toteId),
                        "浅燕麦微廓形西装打破传统黑西装的刻板感，内搭纯白打底与黑色垂感阔腿裤，兼具专业度与松弛感。",
                        4, "面试官印象很好，室内空调略热时内搭很救场。", now),
                buildHistory(uid, null, "学院 Clean Fit · 层次叠穿", baseMonth.withDayOfMonth(20), "咖啡馆办公", "多云", 17, "cloud-sun",
                        List.of(vestId, teeId, jeansId, sneakersId, toteId),
                        "炭黑针织叠穿纯白衬衫打造利落领口层次，搭配深蓝牛仔裤与复古德训鞋，舒适耐看。",
                        5, "走了一万步也不累，德训鞋太百搭了。", now)
        );
        histories.forEach(outfitHistoryMapper::insert);
        log.info("Seeded demo_user (林小满) with {} items, 3 favorite plans, and 4 calendar history records.", items.size());
    }

    private WardrobeItem buildItem(Long userId, String name, String category, String subCategory, String color,
                                   String colorHex, String season, int thickness, List<String> tags, String material,
                                   String svgType, String sourceType, String price, int wearCount, LocalDateTime now) {
        WardrobeItem it = new WardrobeItem();
        it.setUserId(userId);
        it.setName(name);
        it.setCategory(category);
        it.setSubCategory(subCategory);
        it.setColor(color);
        it.setColorHex(colorHex);
        it.setSeason(season);
        it.setThickness(thickness);
        it.setStyleTags(jsonUtils.toJson(tags));
        it.setMaterial(material);
        it.setSvgType(svgType);
        it.setProcessedImageUrl(resolveAltaPieceCutout(svgType, name, color));
        it.setSourceType(sourceType);
        it.setIsManuallyCorrected(0);
        it.setPrice(new BigDecimal(price));
        it.setWearCount(wearCount);
        it.setCreatedAt(now);
        it.setUpdatedAt(now);
        return it;
    }

    private MockProduct buildProduct(String title, String category, String color, String colorHex, String svgType,
                                     List<String> tags, String price, String platform, String url, String desc,
                                     LocalDateTime now) {
        MockProduct p = new MockProduct();
        p.setTitle(title);
        p.setCategory(category);
        p.setColor(color);
        p.setColorHex(colorHex);
        p.setSvgType(svgType);
        p.setStyleTags(jsonUtils.toJson(tags));
        p.setPrice(new BigDecimal(price));
        p.setPlatform(platform);
        p.setImageUrl(resolveAltaPieceCutout(svgType, title, color));
        p.setProductUrl(url);
        p.setDescription(desc);
        p.setCreatedAt(now);
        return p;
    }

    private OutfitHistory buildHistory(Long userId, Long planId, String title, LocalDate date, String scene,
                                       String weather, int temp, String icon, List<Long> itemIds,
                                       String reasoning, int rating, String notes, LocalDateTime now) {
        OutfitHistory h = new OutfitHistory();
        h.setUserId(userId);
        h.setPlanId(planId);
        h.setThemeTitle(title);
        h.setWearDate(date);
        h.setSceneTag(scene);
        h.setWeatherInfo(weather + " " + temp + "℃");
        h.setWeatherTemp(temp);
        h.setWeatherIcon(icon);
        h.setItemIds(jsonUtils.toJson(itemIds));
        h.setAiReasoning(reasoning);
        h.setFeedbackScore(rating);
        h.setNotes(notes);
        h.setCreatedAt(now);
        return h;
    }
}
