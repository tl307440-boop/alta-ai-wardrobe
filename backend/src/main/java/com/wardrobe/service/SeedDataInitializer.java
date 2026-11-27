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
        backfillDemoCuratedItems();
        migrateLegacyCategories();
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
        ensureColumn("user_ai_config", "vton_model", "VARCHAR(100) DEFAULT 'qwen-image-2.1-pro'");
        ensureColumn("user_ai_config", "vton_api_key_encrypted", "TEXT DEFAULT NULL");
        ensureColumn("user_ai_config", "vton_api_key_masked", "VARCHAR(100) DEFAULT NULL");
        ensureColumn("user_ai_config", "vton_public_base_url", "VARCHAR(500) DEFAULT NULL");
        ensureColumn("user_preference", "learned_color_scores", "TEXT DEFAULT NULL");
        ensureColumn("user_preference", "learned_pair_scores", "TEXT DEFAULT NULL");
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

    /**
     * Idempotently backfills the 63 curated demo wardrobe items (demo-p32 .. demo-p84 + /alta/pieces/*.webp)
     * backed by static /alta/pieces/ assets for existing or fresh demo_user accounts.
     */
    public void backfillDemoCuratedItems() {
        SysUser demo = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, "demo_user"));
        if (demo == null) {
            return;
        }
        Long uid = demo.getId();
        List<WardrobeItem> currentItems = wardrobeItemMapper.selectList(
                new LambdaQueryWrapper<WardrobeItem>().eq(WardrobeItem::getUserId, uid));
        Set<String> existingNames = new HashSet<>();
        for (WardrobeItem item : currentItems) {
            if (item.getName() != null) {
                existingNames.add(item.getName().trim());
            }
        }

        LocalDateTime baseTime = LocalDateTime.now();
        List<CuratedDemoSpec> specs = List.of(
                new CuratedDemoSpec("酒红 连衣裙", "dress", "连衣裙", "酒红", "#4D3638", "spring,summer,autumn", 2, List.of("日常百搭"), null, "skirt", "photo", "399.00", 0, "/alta/pieces/demo-p32-crop.jpg", "/alta/pieces/demo-p32-cutout.png", null),
                new CuratedDemoSpec("炭黑 包袋", "bag", "手提包", "炭黑", "#262628", "spring,summer,autumn,winter", 2, List.of("日常百搭"), null, "tote_bag", "photo", "329.00", 0, "/alta/pieces/demo-p33-crop.jpg", "/alta/pieces/demo-p33-cutout.png", null),
                new CuratedDemoSpec("红黑拼接吊带连衣裙", "dress", "吊带连衣裙", "红,黑", "#712622", "summer", 2, List.of("优雅", "时尚", "派对"), "聚酯纤维", "pleated_skirt", "photo", "359.00", 0, "/alta/pieces/demo-p34-crop.jpg", "/alta/pieces/demo-p34-cutout.png", null),
                new CuratedDemoSpec("金属三角项链", "accessory", "项链", "金色", "#FFD700", "spring,summer,autumn,winter", 2, List.of("简约", "时尚"), "金属", "tote_bag", "photo", "159.00", 2, "/alta/pieces/demo-p35-crop.jpg", "/alta/pieces/demo-p35-cutout.png", null),
                new CuratedDemoSpec("炭灰毛领修身短款辣妹夹克", "outerwear", "短款夹克", "炭黑", "#3A3A3C", "spring,autumn,winter", 2, List.of("甜酷辣妹", "Y2K", "御姐"), "斜纹棉拼仿皮草毛领", "suede_jacket", "photo", "389.00", 0, "/alta/pieces/demo-p36-crop.jpg", "/alta/pieces/demo-p36-cutout.png", "短款收腰版型配毛领，辣妹显高显比例"),
                new CuratedDemoSpec("柔粉毛领束腰名媛短款棉服外套", "outerwear", "收腰棉服", "柔粉", "#F4C2CE", "autumn,winter", 3, List.of("芭蕾千金", "纯欲甜酷", "名媛风"), "防风科技面料+毛绒翻领", "cardigan", "photo", "569.00", 0, "/alta/pieces/demo-p37-crop.jpg", "/alta/pieces/demo-p37-cutout.png", "柔粉色收腰腰带配大毛领，冬季显瘦千金风外搭"),
                new CuratedDemoSpec("雪绒球毛领甜美洛丽塔短外套", "outerwear", "洛丽塔外套", "米白", "#F3EDE2", "autumn,winter", 3, List.of("正统萝莉", "甜美少女", "蝴蝶结"), "仿羊羔绒拼蕾丝花边", "cardigan", "photo", "349.00", 0, "/alta/pieces/demo-p38-crop.jpg", "/alta/pieces/demo-p38-cutout.png", "毛绒翻领配小兔耳朵胸针，正统甜美萝莉冬日外搭"),
                new CuratedDemoSpec("奶黄一字肩纯欲修身针织上衣", "top", "一字肩上衣", "米白", "#F5ECCD", "spring,autumn", 1, List.of("纯欲", "温柔韩系", "辣妹"), "软糯弹力细针织", "cardigan", "photo", "249.00", 0, "/alta/pieces/demo-p39-crop.jpg", "/alta/pieces/demo-p39-cutout.png", "温柔奶油黄一字肩露锁骨剪裁，配裙配裤都纯欲显瘦"),
                new CuratedDemoSpec("暗黑漆皮鱼骨束腰性感包臀短裙", "dress", "性感连衣裙", "炭黑", "#1C1C1E", "spring,summer,autumn", 1, List.of("御姐", "性感辣妹", "暗黑风"), "高光弹力漆皮PU", "pleated_skirt", "photo", "459.00", 0, "/alta/pieces/demo-p40-crop.jpg", "/alta/pieces/demo-p40-cutout.png", "杯型胸衣剪裁+紧身包臀廓形，夜场与派对绝对焦点"),
                new CuratedDemoSpec("流光香槟金重工亮片方领短裙", "dress", "派对连衣裙", "卡其", "#D4AF6A", "spring,summer,autumn", 1, List.of("御姐", "轻奢晚宴", "性感纯欲"), "重工满绣亮片网纱", "pleated_skirt", "photo", "529.00", 0, "/alta/pieces/demo-p41-crop.jpg", "/alta/pieces/demo-p41-cutout.png", "大U方领显锁骨与修长天鹅颈，灯光下极度闪耀"),
                new CuratedDemoSpec("裸杏折边抹胸收腰郁金香短裙", "dress", "抹胸连衣裙", "燕麦", "#DFD3C3", "spring,summer,autumn", 1, List.of("法式优雅", "千金风", "性感纯欲"), "高支挺括缎面斜纹", "pleated_skirt", "photo", "429.00", 0, "/alta/pieces/demo-p42-crop.jpg", "/alta/pieces/demo-p42-cutout.png", "抹胸立体剪裁勾勒沙漏腰臀比，高级又性感"),
                new CuratedDemoSpec("酒红深V露背垂感长款礼服裙", "dress", "长款礼服裙", "酒红", "#5E182E", "spring,summer,autumn", 1, List.of("御姐", "性感晚宴", "法式优雅"), "高垂感雪纺蕾丝", "pleated_skirt", "photo", "489.00", 0, "/alta/pieces/demo-p43-crop.jpg", "/alta/pieces/demo-p43-cutout.png", "深V领口与露背线条拉满御姐神秘冷艳气场"),
                new CuratedDemoSpec("暗黑哥特银黑提花束腰洛丽塔JSK", "dress", "哥特洛丽塔", "炭黑", "#262628", "spring,autumn,winter", 2, List.of("暗黑萝莉", "哥特风", "正统洛丽塔"), "暗纹提花缎拼网纱蕾丝", "tshirt", "photo", "580.00", 0, "/alta/pieces/demo-p44-crop.jpg", "/alta/pieces/demo-p44-cutout.png", "胸前蕾丝系带与暗纹提花裙摆，正统暗黑哥特大小姐"),
                new CuratedDemoSpec("草莓庄园蕾丝蝴蝶结甜美洛丽塔裙", "dress", "甜美洛丽塔", "米白", "#F3EDE2", "spring,summer,autumn", 1, List.of("正统萝莉", "甜美少女", "蕾丝蝴蝶结"), "印花纯棉拼水溶蕾丝", "pleated_skirt", "photo", "499.00", 0, "/alta/pieces/demo-p45-crop.jpg", "/alta/pieces/demo-p45-cutout.png", "双蝴蝶结腰饰与精致印花裙摆，经典甜美茶会洛丽塔"),
                new CuratedDemoSpec("芭蕾千金柔粉薄纱蓬蓬仙女短裙", "dress", "仙女连衣裙", "柔粉", "#F4C2CE", "spring,summer", 1, List.of("芭蕾千金", "萝莉甜美", "纯欲仙女"), "多层蓬松法式欧根纱", "pleated_skirt", "photo", "680.00", 0, "/alta/pieces/demo-p46-crop.jpg", "/alta/pieces/demo-p46-cutout.png", "梦幻粉纱蛋糕裙摆配紧身胸衣，出片率极高的在逃公主裙"),
                new CuratedDemoSpec("宫廷黑丝绒拼奶白缎面蝴蝶结短裙", "dress", "吊带短裙", "炭黑", "#262628", "spring,summer,autumn", 1, List.of("纯欲千金", "法式优雅", "性感甜酷"), "丝绒拼真丝质感缎面", "pleated_skirt", "photo", "599.00", 0, "/alta/pieces/demo-p47-crop.jpg", "/alta/pieces/demo-p47-cutout.png", "深V丝绒抹胸撞色奶白缎面裙摆与蝴蝶结，纯欲天花板"),
                new CuratedDemoSpec("星夜宫廷蕾丝一字肩鱼骨束腰上衣", "top", "鱼骨束腰", "米白", "#F3EDE2", "spring,summer,autumn", 1, List.of("纯欲仙女", "芭蕾千金", "法式宫廷"), "精致钩花蕾丝+内置鱼骨", "tshirt", "photo", "329.00", 0, "/alta/pieces/demo-p48-crop.jpg", "/alta/pieces/demo-p48-cutout.png", "微透钩花蕾丝一字肩配收腰鱼骨，勾勒迷人肩颈与纤腰"),
                new CuratedDemoSpec("暗夜暗纹蕾丝透视高开叉长裙", "bottom", "蕾丝半身裙", "炭黑", "#262628", "spring,summer,autumn", 1, List.of("暗黑御姐", "性感纯欲", "甜酷辣妹"), "立体植绒蕾丝网纱", "pleated_skirt", "photo", "279.00", 0, "/alta/pieces/demo-p49-crop.jpg", "/alta/pieces/demo-p49-cutout.png", "半透视植绒蕾丝+高开叉剪裁，若隐若现腿部线条"),
                new CuratedDemoSpec("冰蓝抽绳低腰辣妹百褶超短裙", "bottom", "迷你短裙", "雾霾蓝", "#B9D0DE", "spring,summer", 1, List.of("甜酷辣妹", "Y2K", "纯欲"), "垂感西装混纺", "pleated_skirt", "photo", "199.00", 0, "/alta/pieces/demo-p50-crop.jpg", "/alta/pieces/demo-p50-cutout.png", "腰侧蝴蝶结抽绳设计，清爽辣妹女团风超短裙"),
                new CuratedDemoSpec("奶油不规则荷叶边仙女半身裙", "bottom", "不规则半裙", "纯白", "#FFFFFF", "spring,summer,autumn", 1, List.of("芭蕾风", "纯欲仙女", "温柔韩系"), "轻盈雪纺薄纱", "pleated_skirt", "photo", "239.00", 0, "/alta/pieces/demo-p51-crop.jpg", "/alta/pieces/demo-p51-cutout.png", "前短后长不规则下摆，走动时灵动飘逸显腿长"),
                new CuratedDemoSpec("复古大小姐墨绿高腰百褶半身裙", "bottom", "洛丽塔半裙", "墨绿", "#333D29", "spring,autumn,winter", 2, List.of("正统萝莉", "古典英伦", "法式复古"), "高支毛呢感斜纹", "pleated_skirt", "photo", "289.00", 0, "/alta/pieces/demo-p52-crop.jpg", "/alta/pieces/demo-p52-cutout.png", "高腰束腰双排扣设计，优雅文艺的古典千金风"),
                new CuratedDemoSpec("暗夜玫瑰黑白拼接哥特洛丽塔长裙", "bottom", "洛丽塔半裙", "炭黑", "#262628", "spring,autumn,winter", 2, List.of("暗黑萝莉", "哥特风", "正统洛丽塔"), "多层蕾丝网纱+束腰提花", "pleated_skirt", "photo", "219.00", 0, "/alta/pieces/demo-p53-crop.jpg", "/alta/pieces/demo-p53-cutout.png", "高腰束腰设计拼黑白双层蕾丝裙摆，华丽暗黑大小姐"),
                new CuratedDemoSpec("酒红漆皮尖头露跟细高跟鞋", "shoes", "尖头高跟鞋", "酒红", "#5E182E", "spring,summer,autumn", 1, List.of("御姐", "性感晚宴", "法式优雅"), "高光头层漆皮", "sneakers", "photo", "469.00", 0, "/alta/pieces/demo-p54-crop.jpg", "/alta/pieces/demo-p54-cutout.png", "8.5cm纤细酒杯跟+性感露脚跟后绊带，绝杀御姐战靴"),
                new CuratedDemoSpec("极简黑漆皮尖头细跟露跟高跟鞋", "shoes", "尖头高跟鞋", "炭黑", "#1C1C1E", "spring,summer,autumn", 1, List.of("御姐", "极简通勤", "性感气场"), "镜面亮漆皮", "sneakers", "photo", "459.00", 0, "/alta/pieces/demo-p55-crop.jpg", "/alta/pieces/demo-p55-cutout.png", "凌厉尖头细跟，搭礼服裙与西装裤都气场全开"),
                new CuratedDemoSpec("野性豹纹尖头细跟及膝高筒靴", "shoes", "高筒长靴", "卡其", "#8C6239", "autumn,winter", 2, List.of("御姐", "性感辣妹", "摩登复古"), "丝绒感印花面料", "loafers", "photo", "549.00", 0, "/alta/pieces/demo-p56-crop.jpg", "/alta/pieces/demo-p56-cutout.png", "尖头细高跟修饰小腿线条，搭黑短裙性感又张扬"),
                new CuratedDemoSpec("美拉德深棕麂皮细跟过膝长靴", "shoes", "过膝长靴", "焦糖棕", "#5C3A28", "autumn,winter", 2, List.of("御姐", "性感纯欲", "美拉德复古"), "弹力羊反绒麂皮", "suede_jacket", "photo", "589.00", 0, "/alta/pieces/demo-p57-crop.jpg", "/alta/pieces/demo-p57-cutout.png", "贴合腿型的过膝瘦瘦靴，配超短裙显腿长一米八"),
                new CuratedDemoSpec("极简黑缎面露趾细高跟凉拖鞋", "shoes", "细跟凉拖", "炭黑", "#1C1C1E", "spring,summer,autumn", 1, List.of("御姐", "性感纯欲", "晚宴轻奢"), "光泽真丝缎面", "sneakers", "photo", "319.00", 0, "/alta/pieces/demo-p58-crop.jpg", "/alta/pieces/demo-p58-cutout.png", "极简一字带黑色缎面细跟凉拖，显脚背白皙修长"),
                new CuratedDemoSpec("极简黑不对称挂锁真皮腋下包", "bag", "腋下包", "炭黑", "#1C1C1E", "spring,summer,autumn,winter", 1, List.of("御姐", "极简轻奢", "甜酷辣妹"), "细腻素皮+金属挂锁", "crossbody_bag", "photo", "629.00", 0, "/alta/pieces/demo-p59-crop.jpg", "/alta/pieces/demo-p59-cutout.png", "利落几何翻盖与银色挂锁，提升全身造型高级感"),
                new CuratedDemoSpec("酒红菱格绗缝链条千金单肩包", "bag", "链条包", "酒红", "#5E182E", "spring,summer,autumn,winter", 1, List.of("御姐", "法式千金", "复古轻奢"), "绗缝软羊皮质感", "crossbody_bag", "photo", "699.00", 0, "/alta/pieces/demo-p60-crop.jpg", "/alta/pieces/demo-p60-cutout.png", "浓郁酒红配复古金属链条，搭礼服与大衣都很贵气"),
                new CuratedDemoSpec("黑金绳结扣小香风链条单肩包", "bag", "链条包", "炭黑", "#262628", "spring,summer,autumn,winter", 1, List.of("法式优雅", "千金风", "极简通勤"), "柔韧荔枝纹+金色链条", "crossbody_bag", "photo", "659.00", 0, "/alta/pieces/demo-p61-crop.jpg", "/alta/pieces/demo-p61-cutout.png", "经典黑金配色配雕塑感绳结扣，百搭约会与晚宴"),
                new CuratedDemoSpec("甜美萝莉蕾丝蝴蝶结爱心手提包", "bag", "手提包", "柔粉", "#F4C2CE", "spring,summer,autumn,winter", 1, List.of("正统萝莉", "甜美少女", "蝴蝶结"), "珠光PU+花边蝴蝶结", "crossbody_bag", "photo", "219.00", 0, "/alta/pieces/demo-p62-crop.png", "/alta/pieces/demo-p62-cutout.png", "饱满立体爱心包身与大蝴蝶结，Lo娘出街必备甜心包"),
                new CuratedDemoSpec("暗黑哥特黑兔玩偶洛丽塔斜挎包", "bag", "玩偶包", "炭黑", "#1C1C1E", "spring,autumn,winter", 2, List.of("暗黑萝莉", "哥特风", "甜酷少女"), "长毛绒+丝绒小礼帽", "crossbody_bag", "photo", "239.00", 0, "/alta/pieces/demo-p63-crop.jpg", "/alta/pieces/demo-p63-crop.jpg", "红黑撞色哥特垂耳兔包，背上秒变暗黑系可爱甜心"),
                new CuratedDemoSpec("高腰极简黑色垂感阔腿长裤", "bottom", "阔腿裤", "炭黑", "#1C1C1E", "spring,summer,autumn,winter", 2, List.of("御姐", "极简通勤", "高级感"), "高垂感西装斜纹", "trousers", "photo", "319.00", 0, "/alta/pieces/demo-p64-crop.jpg", "/alta/pieces/demo-p64-cutout.png", "高腰拉长腿部比例，配短款上衣或尖头细高跟气场满分"),
                new CuratedDemoSpec("美拉德可可棕高腰垂感拖地西装裤", "bottom", "西装阔腿裤", "焦糖棕", "#5C3A28", "spring,autumn,winter", 2, List.of("御姐", "美拉德复古", "法式通勤"), "抗皱精纺西装面料", "trousers", "photo", "329.00", 0, "/alta/pieces/demo-p65-crop.jpg", "/alta/pieces/demo-p65-cutout.png", "温柔深棕色调显瘦显腿直，适配秋季御姐风穿搭"),
                new CuratedDemoSpec("灰调细条纹辣妹显瘦微喇长裤", "bottom", "微喇长裤", "炭黑", "#4B4E54", "spring,autumn,winter", 2, List.of("甜酷辣妹", "Y2K", "御姐"), "弹力微喇混纺", "striped_shirt", "photo", "269.00", 0, "/alta/pieces/demo-p66-crop.jpg", "/alta/pieces/demo-p66-cutout.png", "竖条纹微喇裤型修饰腿部线条，显高显瘦"),
                new CuratedDemoSpec("柔粉漆皮双蝴蝶结厚底玛丽珍鞋", "shoes", "玛丽珍鞋", "柔粉", "#F4C2CE", "spring,summer,autumn", 1, List.of("正统萝莉", "甜美少女", "芭蕾千金", "蝴蝶结"), "亮面漆皮+丝带蝴蝶结", "loafers", "photo", "289.00", 0, "/alta/pieces/demo-p67-crop.jpg", "/alta/pieces/demo-p67-cutout.png", "圆头厚底双蝴蝶结玛丽珍，甜美洛丽塔与芭蕾千金专属鞋履"),
                new CuratedDemoSpec("暗黑哥特黑纱蕾丝荷叶边立领宫廷衬衫", "top", "哥特衬衫", "炭黑", "#1C1C1E", "spring,autumn,winter", 1, List.of("暗黑萝莉", "哥特风", "暗夜御姐", "正统洛丽塔"), "微透黑纱拼立领重工蕾丝", "tshirt", "photo", "259.00", 0, "/alta/pieces/demo-p70-crop.jpg", "/alta/pieces/demo-p70-cutout.png", "宫廷立领荷叶边黑蕾丝，暗黑哥特与Livehouse演出核心内搭"),
                new CuratedDemoSpec("法式米白尖头珍珠绊带猫跟单鞋", "shoes", "猫跟单鞋", "米白", "#F3EDE2", "spring,summer,autumn", 1, List.of("法式优雅", "千金风", "温柔韩系", "约会", "晚宴轻奢", "美术馆看展"), "柔韧小羊皮+淡水珍珠绊带", "loafers", "photo", "429.00", 0, "/alta/pieces/demo-p72-crop.jpg", "/alta/pieces/demo-p72-cutout.png", "4cm优雅低猫跟配珍珠踝带，精致舒适显脚踝纤细"),
                new CuratedDemoSpec("极简全黑长款修身西装大衣", "outerwear", "长款西装大衣", "炭黑", "#1C1C1E", "spring,autumn,winter", 3, List.of("全黑冷感", "御姐", "极简通勤", "晚宴轻奢", "显高气场"), "精纺垂感羊毛混纺", "blazer", "photo", "799.00", 0, "/alta/pieces/black-blazer.webp", "/alta/pieces/black-blazer.webp", "利落长款修身H廓形，全黑酷感与晚宴酒会御姐气场外搭"),
                new CuratedDemoSpec("全黑金扣饰修身极简长袖衬衫", "top", "修身衬衫", "炭黑", "#1C1C1E", "spring,autumn,winter", 1, List.of("全黑冷感", "御姐", "极简通勤", "甜酷辣妹"), "高支抗皱精纺棉+领角金扣", "tshirt", "photo", "299.00", 0, "/alta/pieces/black-slim-shirt.webp", "/alta/pieces/black-slim-shirt.webp", "暗门襟极简剪裁点缀金色领角扣，全黑层次穿搭利器"),
                new CuratedDemoSpec("全黑银扣极简直筒及膝骑士长靴", "shoes", "及膝骑士靴", "炭黑", "#1C1C1E", "spring,autumn,winter", 2, List.of("全黑冷感", "显腿长", "御姐", "甜酷辣妹", "暗黑哥特", "极简轻奢"), "细腻头层小牛皮+银色金属扣", "loafers", "photo", "689.00", 0, "/alta/pieces/black-knee-boots.webp", "/alta/pieces/black-knee-boots.webp", "挺括直筒鞋型修饰小腿线条，全黑显腿长冷感与暗黑演出核心战靴"),
                new CuratedDemoSpec("极简全黑高腰直筒开叉长裙", "bottom", "直筒半身裙", "炭黑", "#1C1C1E", "spring,summer,autumn,winter", 2, List.of("全黑冷感", "御姐", "极简通勤", "显腿长"), "高垂感西装斜纹", "pleated_skirt", "photo", "319.00", 0, "/alta/pieces/black-maxi-skirt.webp", "/alta/pieces/black-maxi-skirt.webp", "高腰直筒修饰腿长比例，全黑冷感酷女孩高级感下装"),
                new CuratedDemoSpec("极简全黑银扣大容量通勤托特包", "bag", "大容量托特包", "炭黑", "#1C1C1E", "spring,summer,autumn,winter", 2, List.of("极简通勤", "全黑冷感", "干练面试", "学院风", "装电脑"), "挺括头层牛皮+银色皮带扣", "tote_bag", "photo", "599.00", 0, "/alta/pieces/black-tote.webp", "/alta/pieces/black-tote.webp", "可轻松容纳14寸笔记本电脑，面试、图书馆自习与全黑造型全能包"),
                new CuratedDemoSpec("经典极简纯白垂感通勤学院衬衫", "top", "纯白衬衫", "纯白", "#FFFFFF", "spring,summer,autumn", 1, List.of("学院风", "极简通勤", "干练面试", "复古英伦", "百搭"), "高支免烫长绒棉", "tshirt", "photo", "249.00", 0, "/alta/pieces/white-blouse.webp", "/alta/pieces/white-blouse.webp", "干净挺括的经典白衬衫，叠穿V领针织背心与西装裤/百褶裙极佳"),
                new CuratedDemoSpec("复古深咖双排扣英伦羊毛大衣", "outerwear", "羊毛大衣", "焦糖棕", "#5C3A28", "autumn,winter", 3, List.of("复古英伦", "学院风", "美拉德复古", "保暖", "图书馆"), "80%双面澳毛呢", "trench", "photo", "899.00", 0, "/alta/pieces/brown-wool-coat.webp", "/alta/pieces/brown-wool-coat.webp", "温润深咖长款双排扣羊毛大衣，降温天去图书馆自习保暖又有英伦学院感"),
                new CuratedDemoSpec("奶白复古麻花针织学院毛衣", "top", "针织毛衣", "米白", "#F3EDE2", "spring,autumn,winter", 2, List.of("学院风", "复古英伦", "温柔韩系", "图书馆", "保暖"), "软糯美利奴羊毛绞花", "cardigan", "photo", "349.00", 0, "/alta/pieces/cream-polo-knit.webp", "/alta/pieces/cream-polo-knit.webp", "经典奶白绞花学院风针织毛衣，保暖舒适配墨绿百褶裙或格纹裤绝佳"),
                new CuratedDemoSpec("复古英伦焦糖格纹高腰阔腿裤", "bottom", "格纹阔腿裤", "卡其", "#9B7E5C", "spring,autumn,winter", 2, List.of("复古英伦", "学院风", "美拉德复古", "图书馆", "复古休闲"), "精纺复古小格纹毛呢", "trousers", "photo", "339.00", 0, "/alta/pieces/checked-wide-pants.webp", "/alta/pieces/checked-wide-pants.webp", "经典英伦格纹直筒阔腿剪裁，图书馆复古学院与秋冬咖啡厅百搭"),
                new CuratedDemoSpec("极简黑亮面厚底英伦乐福鞋", "shoes", "皮质乐福鞋", "炭黑", "#1C1C1E", "spring,autumn,winter", 2, List.of("学院风", "极简通勤", "干练面试", "全黑冷感", "复古英伦"), "抛光头层牛皮", "loafers", "photo", "499.00", 0, "/alta/pieces/black-loafers.webp", "/alta/pieces/black-loafers.webp", "经典黑色圆头乐福鞋，面试通勤、复古学院与全黑出街百搭"),
                new CuratedDemoSpec("香槟米真丝缎面法式垂感衬衫", "top", "缎面衬衫", "燕麦", "#DFD3C3", "spring,summer,autumn", 1, List.of("法式优雅", "千金风", "美术馆看展", "温柔韩系", "轻奢高级"), "光泽真丝醋酸缎面", "tshirt", "photo", "389.00", 0, "/alta/pieces/beige-satin-shirt.webp", "/alta/pieces/beige-satin-shirt.webp", "自带柔光珍珠光泽的香槟米缎面衬衫，美术馆看展与法式轻奢绝配"),
                new CuratedDemoSpec("法式浅燕麦双排扣系带羊毛大衣", "outerwear", "系带羊毛大衣", "燕麦", "#DFD3C3", "spring,autumn,winter", 3, List.of("法式优雅", "千金风", "美术馆看展", "温柔韩系", "极简通勤"), "高支双面精纺羊毛", "trench", "photo", "959.00", 0, "/alta/pieces/beige-belted-coat.webp", "/alta/pieces/beige-belted-coat.webp", "收腰系带大翻领浅燕麦大衣，法式千金看展与高级通勤必备"),
                new CuratedDemoSpec("法式奶白金扣复古马鞍腋下包", "bag", "马鞍腋下包", "米白", "#F5F2EB", "spring,summer,autumn,winter", 1, List.of("法式优雅", "千金风", "美术馆看展", "甜美少女", "温柔韩系"), "精致浮雕小牛皮+做旧金扣", "crossbody_bag", "photo", "689.00", 0, "/alta/pieces/white-saddle-bag.webp", "/alta/pieces/white-saddle-bag.webp", "经典奶白流线马鞍包配金色D字吊坠，法式千金与甜美系亮眼点缀"),
                new CuratedDemoSpec("暗夜蕾丝花边超薄透肤黑丝袜", "accessory", "过膝丝袜", "炭黑", "#1C1C1E", "spring,summer,autumn,winter", 1, List.of("性感纯欲", "黑丝", "暗黑辣妹", "JK制服", "夜店派对"), "超薄天鹅绒丝质+法式花边蕾丝", "tote_bag", "photo", "59.00", 0, "/alta/pieces/demo-p73-crop.jpg", "/alta/pieces/demo-p73-cutout.png", "高挑蕾丝花边透肤黑丝过膝袜，搭超短裙与JK百褶裙纯欲吸睛"),
                new CuratedDemoSpec("法式蕾丝蝴蝶结吊带过膝黑丝袜", "accessory", "吊带丝袜", "炭黑", "#1C1C1E", "spring,summer,autumn,winter", 1, List.of("性感纯欲", "黑丝", "暗黑辣妹", "御姐", "夜店派对"), "透肤网纱蕾丝+吊袜带+丝绒带", "tote_bag", "photo", "89.00", 0, "/alta/pieces/demo-p74-crop.jpg", "/alta/pieces/demo-p74-cutout.png", "法式蕾丝吊袜带与透肤黑丝组合，暗夜氛围与性感辣妹进阶配饰"),
                new CuratedDemoSpec("暗黑辣妹菱格渔网镂空丝袜", "accessory", "渔网丝袜", "炭黑", "#1C1C1E", "spring,summer,autumn", 1, List.of("暗黑辣妹", "性感纯欲", "Y2K", "夜店派对", "渔网黑丝"), "高弹力菱格渔网锦纶", "tote_bag", "photo", "49.00", 0, "/alta/pieces/demo-p75-crop.jpg", "/alta/pieces/demo-p75-cutout.png", "镂空菱格渔网丝袜，配皮质短裙与长靴瞬间拉满Y2K辣妹张力"),
                new CuratedDemoSpec("日系收腰短袖海军领格纹结JK水手服上衣", "top", "JK水手服", "纯白", "#FFFFFF", "spring,summer,autumn", 1, List.of("JK制服", "日系学院", "纯欲甜辣", "水手服"), "挺括制服斜纹棉+格纹领结", "tshirt", "photo", "169.00", 0, "/alta/pieces/demo-p76-crop.jpg", "/alta/pieces/demo-p76-cutout.png", "经典收腰短款海军领JK水手服上衣，配格纹百褶短裙与黑丝/堆堆袜绝配"),
                new CuratedDemoSpec("复古焦糖格纹高腰JK百褶超短裙", "bottom", "JK格裙", "焦糖棕", "#6E4733", "spring,summer,autumn", 1, List.of("JK制服", "日系学院", "百褶短裙", "甜酷辣妹", "格纹"), "高温定型TR制服呢", "pleated_skirt", "photo", "159.00", 0, "/alta/pieces/demo-p77-crop.jpg", "/alta/pieces/demo-p77-cutout.png", "锋利刀褶高腰显腿长JK格裙，适配水手服、短款辣妹上衣与过膝黑丝"),
                new CuratedDemoSpec("燕麦细格纹系带高腰JK百褶超短裙", "bottom", "JK格裙", "燕麦", "#D2CABD", "spring,summer,autumn", 1, List.of("JK制服", "日系学院", "百褶短裙", "纯欲甜辣", "格纹"), "高温定型TR制服呢", "pleated_skirt", "photo", "169.00", 0, "/alta/pieces/demo-p78-crop.jpg", "/alta/pieces/demo-p78-cutout.png", "高腰百褶系带细格纹JK短裙，搭堆堆袜与黑丝清纯又显腿长"),
                new CuratedDemoSpec("日系软糯纯白粗针织宽松堆堆袜", "accessory", "堆堆袜", "纯白", "#FAF8F5", "spring,autumn,winter", 2, List.of("JK制服", "堆堆袜", "日系学院", "Y2K", "纯欲辣妹"), "软糯粗针绞花精梳棉", "tote_bag", "photo", "39.00", 0, "/alta/pieces/demo-p79-crop.jpg", "/alta/pieces/demo-p79-cutout.png", "软糯粗针织宽松堆堆袜套，修饰小腿线条，JK制服与乐福鞋点睛配饰"),
                new CuratedDemoSpec("Y2K暗黑辣妹炭黑针织微喇堆堆袜", "accessory", "堆堆袜", "炭黑", "#1C1C1E", "spring,autumn,winter", 2, List.of("Y2K", "暗黑辣妹", "堆堆袜", "JK制服", "甜酷辣妹"), "弹力微喇粗针棉混纺", "tote_bag", "photo", "45.00", 0, "/alta/pieces/demo-p80-crop.jpg", "/alta/pieces/demo-p80-cutout.png", "炭黑微喇五角星织纹堆堆袜，甜酷Y2K辣妹与暗黑JK百褶裙必备"),
                new CuratedDemoSpec("纯欲辣妹黑色挂脖深V前扣露背短上衣", "top", "挂脖露背上衣", "炭黑", "#1C1C1E", "spring,summer,autumn", 1, List.of("性感辣妹", "纯欲", "露背", "夜店派对", "暗黑风"), "哑光弹力仿小羊皮+金属前扣", "tshirt", "photo", "229.00", 0, "/alta/pieces/demo-p81-crop.jpg", "/alta/pieces/demo-p81-cutout.png", "深V前扣+大露背挂脖剪裁勾勒肩颈与腰线，性感辣妹夜场与约会核心单品"),
                new CuratedDemoSpec("暗黑性感漆皮镂空交叉绑带挂脖短上衣", "top", "绑带露背上衣", "炭黑", "#1C1C1E", "spring,summer,autumn", 1, List.of("暗黑辣妹", "性感纯欲", "Y2K", "夜店派对", "镂空绑带"), "光泽PU皮革+金属鸡眼绑带", "tshirt", "photo", "249.00", 0, "/alta/pieces/demo-p82-crop.jpg", "/alta/pieces/demo-p82-cutout.png", "胸前交叉镂空金属鸡眼绑带配修身皮革廓形，高攻击性性感辣妹短上衣"),
                new CuratedDemoSpec("极简全黑高腰辣妹包臀A字超短裙", "bottom", "超短裙", "炭黑", "#1C1C1E", "spring,summer,autumn", 1, List.of("性感辣妹", "纯欲", "全黑冷感", "夜店派对", "超短裙"), "高弹修身挺括斜纹", "pleated_skirt", "photo", "199.00", 0, "/alta/pieces/black-mini-skirt.webp", "/alta/pieces/black-mini-skirt.webp", "高腰极简黑色包臀A字超短裙，百搭挂脖露背上衣、黑丝与渔网袜"),
                new CuratedDemoSpec("酒红深V铆钉挂脖褶皱露背超短连衣裙", "dress", "性感连衣裙", "酒红", "#5E182E", "spring,summer,autumn", 1, List.of("性感辣妹", "纯欲", "御姐", "夜店派对", "露背"), "高弹抓褶亲肤针织+金属铆钉", "pleated_skirt", "photo", "359.00", 0, "/alta/pieces/demo-p84-crop.jpg", "/alta/pieces/demo-p84-cutout.png", "浓郁酒红深V挂脖配修身抓褶包臀剪裁，清吧派对与约会一眼惊艳")
        );

        int inserted = 0;
        for (int i = 0; i < specs.size(); i++) {
            CuratedDemoSpec spec = specs.get(i);
            if (existingNames.contains(spec.name())) {
                continue;
            }
            wardrobeItemMapper.insert(buildCuratedDemoItem(uid, spec, baseTime.plusSeconds(i)));
            existingNames.add(spec.name());
            inserted++;
        }
        if (inserted > 0) {
            log.info("Backfilled {} curated demo wardrobe items for demo_user.", inserted);
        }
    }

    /**
     * Idempotent category migration for existing H2 / MySQL volumes created under the old 5-category model:
     * moves bags out of 'accessory' into 'bag' and one-piece dresses out of 'top' into 'dress'.
     */
    public void migrateLegacyCategories() {
        int movedBags = 0;
        int movedDresses = 0;
        int patchedSub = 0;

        List<WardrobeItem> allItems = wardrobeItemMapper.selectList(null);
        for (WardrobeItem it : allItems) {
            boolean dirty = false;
            String cat = it.getCategory();
            if ("accessory".equalsIgnoreCase(cat) && WardrobeService.isBagMeta(it.getName(), it.getSubCategory())) {
                it.setCategory("bag");
                if (it.getSubCategory() == null || it.getSubCategory().isBlank()) {
                    it.setSubCategory("包袋");
                }
                movedBags++;
                dirty = true;
            } else if ("accessory".equalsIgnoreCase(cat)
                    && it.getName() != null && it.getName().contains("项链")
                    && (it.getSubCategory() == null || it.getSubCategory().isBlank())) {
                it.setSubCategory("项链");
                patchedSub++;
                dirty = true;
            } else if ("top".equalsIgnoreCase(cat) && OutfitRecommendationService.isOnePieceEntity(it)) {
                it.setCategory("dress");
                if (it.getSubCategory() == null || it.getSubCategory().isBlank()) {
                    it.setSubCategory("连衣裙");
                }
                movedDresses++;
                dirty = true;
            }
            if (dirty) {
                wardrobeItemMapper.updateById(it);
            }
        }

        int movedProducts = 0;
        List<MockProduct> products = mockProductMapper.selectList(null);
        for (MockProduct p : products) {
            if ("accessory".equalsIgnoreCase(p.getCategory()) && WardrobeService.isBagMeta(p.getTitle(), null)) {
                p.setCategory("bag");
                mockProductMapper.updateById(p);
                movedProducts++;
            }
        }

        if (movedBags > 0 || movedDresses > 0 || movedProducts > 0 || patchedSub > 0) {
            log.info("Migrated legacy categories: {} items -> bag, {} items -> dress, {} products -> bag, {} subCategories.",
                    movedBags, movedDresses, movedProducts, patchedSub);
        }
    }

    private record CuratedDemoSpec(
            String name, String category, String subCategory, String color, String colorHex,
            String season, int thickness, List<String> tags, String material, String svgType,
            String sourceType, String price, int wearCount,
            String originalImageUrl, String processedImageUrl, String remark) {}

    private WardrobeItem buildCuratedDemoItem(Long userId, CuratedDemoSpec spec, LocalDateTime timestamp) {
        WardrobeItem it = new WardrobeItem();
        it.setUserId(userId);
        it.setName(spec.name());
        it.setCategory(spec.category());
        it.setSubCategory(spec.subCategory());
        it.setColor(spec.color());
        it.setColorHex(spec.colorHex());
        it.setSeason(spec.season());
        it.setThickness(spec.thickness());
        it.setStyleTags(jsonUtils.toJson(spec.tags()));
        it.setMaterial(spec.material());
        it.setSvgType(spec.svgType());
        it.setOriginalImageUrl(spec.originalImageUrl());
        it.setProcessedImageUrl(spec.processedImageUrl());
        it.setSourceType(spec.sourceType());
        it.setIsManuallyCorrected(spec.remark() != null && !spec.remark().isBlank() ? 1 : 0);
        it.setPrice(new BigDecimal(spec.price()));
        it.setWearCount(spec.wearCount());
        it.setRemark(spec.remark());
        it.setCreatedAt(timestamp);
        it.setUpdatedAt(timestamp);
        return it;
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
                buildProduct("哑光软皮大容量极简通勤托特包", "bag", "炭黑", "#262628", "tote_bag",
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
                buildProduct("法式复古焦糖牛皮马鞍腋下包", "bag", "焦糖棕", "#8A5230", "crossbody_bag",
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
                buildItem(uid, "燕麦帆布拼皮托特包", "bag", "托特包", "燕麦", "#DFD3C3", "spring,summer,autumn,winter", 2,
                        List.of("极简通勤", "温柔韩系"), "荔枝纹牛皮", "tote_bag", "photo", "389.00", 15, now),
                buildItem(uid, "焦糖复古马鞍腋下包", "bag", "腋下包", "焦糖棕", "#8A5230", "spring,autumn,winter", 2,
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
