package com.wardrobe.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wardrobe.common.JsonUtils;
import com.wardrobe.mapper.InspoLikeMapper;
import com.wardrobe.model.dto.InspoDto;
import com.wardrobe.model.entity.InspoLike;
import com.wardrobe.model.entity.UserPreference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * China-localized Alta Editorial Inspiration Hub ("灵感发现").
 * Powered by locally cached Alta Daily public transparent studio model cutouts (/alta/looks/*)
 * and exact outfit-matched segmented garment/jewelry cutouts (/alta/pieces/*),
 * paired with China e-commerce (Taobao & JD) direct search/purchase links.
 */
@Service
@RequiredArgsConstructor
public class InspoService {

    private final InspoLikeMapper inspoLikeMapper;
    private final PreferenceService preferenceService;
    private final JsonUtils jsonUtils;

    private static final List<InspoDto.BannerVo> BANNERS = List.of(
            InspoDto.BannerVo.builder()
                    .id("bn-1")
                    .eyebrow("智搭独家企划 · SHANGHAI EDIT")
                    .title("都市静奢通勤")
                    .subtitle("奶油廓形西装 × 垂感阔腿裤 · 一键探索淘宝与京东同款")
                    .ctaLabel("点击探索")
                    .brandFilter("极简通勤")
                    .bgStyle("linear-gradient(125deg, #F5F5F5 0%, #EAEAEA 100%)")
                    .textTone("dark")
                    .imageUrl("/alta/looks/w2.webp")
                    .build(),
            InspoDto.BannerVo.builder()
                    .id("bn-2")
                    .eyebrow("设计师配饰企划 · GOLD HOOPS")
                    .title("金属耳饰与包袋点睛")
                    .subtitle("雕塑感金饰 / 荔枝纹托特 / 麂皮新月包 · 高质感穿搭平替严选")
                    .ctaLabel("去逛同款")
                    .brandFilter("配饰")
                    .bgStyle("linear-gradient(125deg, #F7F5F2 0%, #ECE6DF 100%)")
                    .textTone("dark")
                    .imageUrl("/alta/pieces/gold-hoop-earrings.webp")
                    .build()
    );

    private static final List<InspoDto.BrandCollectionVo> BRANDS = List.of(
            InspoDto.BrandCollectionVo.builder()
                    .id("br-icicle")
                    .name("ICICLE")
                    .cnName("之禾")
                    .origin("上海 · 东方静奢")
                    .tagline("天然羊绒与奶油极简廓形，定义都市高级静奢通勤")
                    .priceBand("¥1,200 - ¥5,800")
                    .taobaoFlagshipUrl(taobaoUrl("ICICLE 之禾 官方旗舰店 大衣 西装"))
                    .jdFlagshipUrl(jdUrl("ICICLE 之禾 女装"))
                    .lookIds(List.of("look-w2", "look-w3", "look-w8"))
                    .coverImageUrl("/alta/looks/w2.webp")
                    .build(),
            InspoDto.BrandCollectionVo.builder()
                    .id("br-jnby")
                    .name("JNBY")
                    .cnName("江南布衣")
                    .origin("杭州 · 独立设计")
                    .tagline("松弛针织叠穿与解构剪裁，高辨识度都市文艺日常")
                    .priceBand("¥490 - ¥2,200")
                    .taobaoFlagshipUrl(taobaoUrl("JNBY 江南布衣 官方旗舰店"))
                    .jdFlagshipUrl(jdUrl("JNBY 江南布衣"))
                    .lookIds(List.of("look-w1", "look-w4", "look-m1"))
                    .coverImageUrl("/alta/looks/w4.webp")
                    .build(),
            InspoDto.BrandCollectionVo.builder()
                    .id("br-songmont")
                    .name("Songmont")
                    .cnName("山下有松")
                    .origin("北京 · 东方包袋美学")
                    .tagline("植鞣头层牛皮与新月腋下包型，点亮极简通勤造型")
                    .priceBand("¥1,180 - ¥2,980")
                    .taobaoFlagshipUrl(taobaoUrl("Songmont 山下有松 官方旗舰店"))
                    .jdFlagshipUrl(jdUrl("Songmont 山下有松 包"))
                    .lookIds(List.of("look-w7", "look-w8", "look-w9"))
                    .coverImageUrl("/alta/pieces/taupe-crescent-bag.webp")
                    .build(),
            InspoDto.BrandCollectionVo.builder()
                    .id("br-hefang")
                    .name("HEFANG")
                    .cnName("何方珠宝")
                    .origin("深圳 · 轻奢设计师配饰")
                    .tagline("几何金属圆环耳饰与叠戴手镯，点亮领口与耳畔光泽")
                    .priceBand("¥260 - ¥1,380")
                    .taobaoFlagshipUrl(taobaoUrl("HEFANG 何方珠宝 官方旗舰店 耳环"))
                    .jdFlagshipUrl(jdUrl("HEFANG 何方珠宝 耳饰"))
                    .lookIds(List.of("look-w1", "look-w5", "look-w7"))
                    .coverImageUrl("/alta/pieces/gold-knot-earrings.webp")
                    .build(),
            InspoDto.BrandCollectionVo.builder()
                    .id("br-ur")
                    .name("URBAN REVIVO")
                    .cnName("UR · 都市快尚")
                    .origin("广州 · 摩登通勤")
                    .tagline("利落廓形西装与百搭裙装，年轻职场高性价比首选")
                    .priceBand("¥159 - ¥699")
                    .taobaoFlagshipUrl(taobaoUrl("URBAN REVIVO 官方旗舰店"))
                    .jdFlagshipUrl(jdUrl("URBAN REVIVO 女装"))
                    .lookIds(List.of("look-w5", "look-w10", "look-w11"))
                    .coverImageUrl("/alta/looks/w10.webp")
                    .build(),
            InspoDto.BrandCollectionVo.builder()
                    .id("br-cos")
                    .name("COS")
                    .cnName("建筑感极简男装")
                    .origin("都会 Clean Fit · 男装")
                    .tagline("精纺双排扣大衣与极简垂感西裤，打造干净松弛气场")
                    .priceBand("¥390 - ¥1,900")
                    .taobaoFlagshipUrl(taobaoUrl("COS 官方旗舰店 男装 大衣"))
                    .jdFlagshipUrl(jdUrl("COS 极简 男士 西装 大衣"))
                    .lookIds(List.of("look-m1", "look-m2", "look-m3"))
                    .coverImageUrl("/alta/looks/m2.webp")
                    .build()
    );

    private final List<InspoDto.InspoLookVo> catalog = buildCatalog();

    public InspoDto.FeedResponse getFeed(Long userId, String tab, String gender, String keyword, boolean likedOnly) {
        Set<String> likedIds = inspoLikeMapper.selectList(
                        new LambdaQueryWrapper<InspoLike>().eq(InspoLike::getUserId, userId))
                .stream()
                .map(InspoLike::getLookId)
                .collect(Collectors.toSet());

        UserPreference pref = preferenceService.getOrCreateEntity(userId);
        List<String> favStyles = jsonUtils.toStringList(pref.getFavoriteStyles());
        List<String> favColors = jsonUtils.toStringList(pref.getFavoriteColors());
        List<String> dislikedColors = jsonUtils.toStringList(pref.getDislikedColors());
        Map<String, Double> styleWeights = jsonUtils.toDoubleMap(pref.getStyleWeights());
        List<String> seasonColors = PreferenceService.getSeasonRecommendedColors(pref.getColorSeason());

        String g = gender != null ? gender.trim().toLowerCase() : "all";
        String kw = keyword != null ? keyword.trim().toLowerCase() : "";

        List<InspoDto.InspoLookVo> filtered = catalog.stream()
                .map(look -> cloneWithPersonalization(look, likedIds.contains(look.getId()),
                        pref, favStyles, favColors, dislikedColors, styleWeights, seasonColors))
                .filter(look -> !likedOnly || look.isLiked())
                .filter(look -> "all".equals(g) || g.isBlank() || g.equalsIgnoreCase(look.getGender()))
                .filter(look -> matchesKeyword(look, kw))
                .collect(Collectors.toList());

        if ("top".equalsIgnoreCase(tab)) {
            filtered.sort((a, b) -> Integer.compare(b.getViewsCount(), a.getViewsCount()));
            for (int i = 0; i < filtered.size(); i++) {
                filtered.get(i).setRank(i + 1);
            }
        } else {
            filtered.sort((a, b) -> {
                int cmp = Integer.compare(b.getMatchScore(), a.getMatchScore());
                return cmp != 0 ? cmp : Integer.compare(b.getViewsCount(), a.getViewsCount());
            });
        }

        return InspoDto.FeedResponse.builder()
                .banners(BANNERS)
                .looks(filtered)
                .brands(BRANDS)
                .likedCount(likedIds.size())
                .build();
    }

    public InspoDto.LikeToggleVo toggleLike(Long userId, String lookId) {
        InspoLike existing = inspoLikeMapper.selectOne(
                new LambdaQueryWrapper<InspoLike>()
                        .eq(InspoLike::getUserId, userId)
                        .eq(InspoLike::getLookId, lookId));

        boolean nowLiked;
        if (existing != null) {
            inspoLikeMapper.deleteById(existing.getId());
            nowLiked = false;
        } else {
            InspoLike entity = new InspoLike();
            entity.setUserId(userId);
            entity.setLookId(lookId);
            entity.setCreatedAt(LocalDateTime.now());
            inspoLikeMapper.insert(entity);
            nowLiked = true;
        }

        InspoDto.InspoLookVo targetLook = catalog.stream()
                .filter(l -> l.getId().equals(lookId))
                .findFirst()
                .orElse(null);

        if (targetLook != null && targetLook.getStyleTags() != null && !targetLook.getStyleTags().isEmpty()) {
            preferenceService.reinforceStyleTags(userId, targetLook.getStyleTags(), nowLiked ? 0.03 : -0.01);
        }

        int baseLikes = targetLook != null ? targetLook.getLikesCount() : 60;

        return InspoDto.LikeToggleVo.builder()
                .lookId(lookId)
                .liked(nowLiked)
                .likesCount(baseLikes + (nowLiked ? 1 : 0))
                .build();
    }

    private InspoDto.InspoLookVo cloneWithPersonalization(
            InspoDto.InspoLookVo src,
            boolean liked,
            UserPreference pref,
            List<String> favStyles,
            List<String> favColors,
            List<String> dislikedColors,
            Map<String, Double> styleWeights,
            List<String> seasonColors) {
        double score = 74.0;
        String reason = "都市精选高分搭配";

        List<String> tags = src.getStyleTags() != null ? src.getStyleTags() : Collections.emptyList();
        String matchedStyle = null;
        double bestWeight = 0.0;
        for (String tag : tags) {
            if (favStyles.contains(tag)) {
                score += 11.0;
                matchedStyle = tag;
            }
            double w = styleWeights.getOrDefault(tag, 0.0);
            if (w > bestWeight) {
                bestWeight = w;
                if (matchedStyle == null && w >= 0.72) {
                    matchedStyle = tag;
                }
            }
        }
        score += bestWeight * 6.0;

        boolean hitFavColor = false;
        boolean hitSeasonColor = false;
        boolean hitDisliked = false;
        if (src.getPieces() != null) {
            for (InspoDto.LookPieceVo p : src.getPieces()) {
                String c = p.getColor();
                if (c == null) continue;
                if (favColors.contains(c)) hitFavColor = true;
                if (seasonColors.contains(c)) hitSeasonColor = true;
                if (dislikedColors.contains(c)) hitDisliked = true;
            }
        }
        if (hitFavColor) score += 5.0;
        if (hitSeasonColor) score += 4.0;
        if (hitDisliked) score -= 12.0;
        if (liked) score += 3.0;

        if (matchedStyle != null && hitSeasonColor) {
            reason = "契合「" + matchedStyle + "」× " + Objects.toString(pref.getColorSeason(), "中性百搭");
        } else if (matchedStyle != null) {
            reason = "命中偏好风格 · " + matchedStyle;
        } else if (hitSeasonColor) {
            reason = "契合你的" + Objects.toString(pref.getColorSeason(), "中性百搭") + "色板";
        } else if (hitFavColor) {
            reason = "命中你的高频偏好色系";
        }

        int finalScore = Math.max(68, Math.min(99, (int) Math.round(score)));

        return InspoDto.InspoLookVo.builder()
                .id(src.getId())
                .creatorName(src.getCreatorName())
                .creatorHandle(src.getCreatorHandle())
                .city(src.getCity())
                .gender(src.getGender())
                .title(src.getTitle())
                .styleTag(src.getStyleTag())
                .seasonTag(src.getSeasonTag())
                .viewsText(src.getViewsText())
                .viewsCount(src.getViewsCount())
                .rank(src.getRank())
                .likesCount(src.getLikesCount() + (liked ? 1 : 0))
                .commentsCount(src.getCommentsCount())
                .restylesCount(src.getRestylesCount())
                .liked(liked)
                .matchScore(finalScore)
                .matchReason(reason)
                .modelImageUrl(src.getModelImageUrl())
                .fallbackSvgType(src.getFallbackSvgType())
                .styleTags(src.getStyleTags())
                .pieces(src.getPieces())
                .sampleComments(src.getSampleComments())
                .build();
    }

    private boolean matchesKeyword(InspoDto.InspoLookVo look, String kw) {
        if (kw.isBlank()) return true;
        StringBuilder sb = new StringBuilder();
        sb.append(look.getCreatorName()).append(' ')
                .append(look.getCity()).append(' ')
                .append(look.getTitle()).append(' ')
                .append(look.getStyleTag()).append(' ');
        if (look.getStyleTags() != null) {
            look.getStyleTags().forEach(t -> sb.append(t).append(' '));
        }
        if (look.getPieces() != null) {
            for (InspoDto.LookPieceVo p : look.getPieces()) {
                sb.append(p.getBrand()).append(' ')
                        .append(p.getName()).append(' ')
                        .append(p.getSlotLabel()).append(' ')
                        .append(p.getColor()).append(' ');
            }
        }
        return sb.toString().toLowerCase().contains(kw);
    }

    private static String taobaoUrl(String keyword) {
        return "https://s.taobao.com/search?q=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
    }

    private static String jdUrl(String keyword) {
        return "https://search.jd.com/Search?keyword=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
    }

    private static InspoDto.SimilarProductVo sim(String id, String brand, String title, String price, String origPrice,
                                                 String platform, String imgUrl, String svgType, String colorHex) {
        String label = "jd".equals(platform) ? "京东自营" : ("tmall".equals(platform) ? "天猫旗舰" : "淘宝精选");
        String tb = taobaoUrl(brand + " " + title);
        String jd = jdUrl(brand + " " + title);
        return InspoDto.SimilarProductVo.builder()
                .id(id)
                .brand(brand)
                .title(title)
                .price(new BigDecimal(price))
                .originalPrice(origPrice != null ? new BigDecimal(origPrice) : null)
                .platform(platform)
                .platformLabel(label)
                .imageUrl(imgUrl)
                .svgType(svgType)
                .colorHex(colorHex)
                .buyUrl("jd".equals(platform) ? jd : tb)
                .taobaoUrl(tb)
                .jdUrl(jd)
                .build();
    }

    private static List<InspoDto.SimilarProductVo> earringSimilars() {
        return List.of(
                sim("sim-er-1", "HEFANG 何方", "极简水滴雕塑感镀金耳环", "369.00", "459.00", "tmall", "/alta/pieces/gold-hoop-earrings.webp", "crossbody_bag", "#D4AF37"),
                sim("sim-er-2", "JENNY BIRD 同款", "法式绳结立体金属耳圈", "158.00", "228.00", "taobao", "/alta/pieces/gold-knot-earrings.webp", "crossbody_bag", "#D4AF37"),
                sim("sim-er-3", "周大生 轻奢", "925纯银极简几何素圈耳钉", "199.00", null, "jd", "/alta/pieces/silver-hoop-earrings.webp", "crossbody_bag", "#C0C0C0"),
                sim("sim-er-4", "YVMIN 尤目", "巴洛克珍珠金色复古耳钉", "320.00", "380.00", "tmall", "/alta/pieces/gold-pearl-earrings.webp", "crossbody_bag", "#D4AF37"),
                sim("sim-er-5", "APM Monaco 风格", "法式四叶草金色锁骨链", "268.00", null, "jd", "/alta/pieces/gold-clover-necklace.webp", "crossbody_bag", "#D4AF37"),
                sim("sim-er-6", "素觉 小众设计", "极简双层打结镀金手镯", "168.00", "218.00", "taobao", "/alta/pieces/gold-knot-bangle.webp", "crossbody_bag", "#D4AF37"),
                sim("sim-er-7", "Tory Burch 风格", "天然白贝母金色精致吊坠项链", "245.00", "310.00", "jd", "/alta/pieces/gold-stone-necklace.webp", "crossbody_bag", "#D4AF37"),
                sim("sim-er-8", "冷淡风银饰", "925银极简蛇骨吊坠锁骨链", "118.00", null, "taobao", "/alta/pieces/silver-pendant-necklace.webp", "crossbody_bag", "#C0C0C0")
        );
    }

    private static List<InspoDto.SimilarProductVo> watchSimilars() {
        return List.of(
                sim("sim-wt-1", "LOLA ROSE", "复古小方盘黑金轻奢女表", "1180.00", "1480.00", "tmall", "/alta/pieces/gold-watch.webp", "crossbody_bag", "#D4AF37"),
                sim("sim-wt-2", "Daniel Wellington", "经典精钢银白盘通勤腕表", "990.00", "1290.00", "jd", "/alta/pieces/silver-watch.webp", "crossbody_bag", "#C0C0C0"),
                sim("sim-wt-3", "CASIO 卡西欧", "黑金钢带极简石英腕表", "459.00", null, "jd", "/alta/pieces/black-gold-watch.webp", "crossbody_bag", "#1E1E1E"),
                sim("sim-wt-4", "SEIKO 精工", "银黑机械大三针商务通勤表", "1280.00", "1580.00", "tmall", "/alta/pieces/seiko-watch.webp", "crossbody_bag", "#262628"),
                sim("sim-wt-5", "CELINE 风格", "极简椭圆黑框猫眼太阳镜", "299.00", "399.00", "taobao", "/alta/pieces/celine-sunglasses.webp", "crossbody_bag", "#1E1E1E"),
                sim("sim-wt-6", "法式真丝方巾", "红蓝条纹撞色桑蚕丝领巾", "138.00", null, "taobao", "/alta/pieces/silk-scarf.webp", "crossbody_bag", "#8A5230")
        );
    }

    private static List<InspoDto.SimilarProductVo> bagSimilars() {
        return List.of(
                sim("sim-bg-1", "Songmont 山下有松", "荔枝纹头层牛皮奶油托特包", "1580.00", null, "tmall", "/alta/pieces/cream-tote.webp", "tote_bag", "#F3EDE2"),
                sim("sim-bg-2", "Miu Miu 风格平替", "复古焦糖棕植鞣牛皮腋下包", "329.00", "459.00", "taobao", "/alta/pieces/brown-shoulder-bag.webp", "crossbody_bag", "#8A5230"),
                sim("sim-bg-3", "Coccinelle 风格", "灰棕翻毛麂皮新月单肩腋下包", "468.00", "599.00", "jd", "/alta/pieces/taupe-crescent-bag.webp", "crossbody_bag", "#C7B299"),
                sim("sim-bg-4", "极简通勤皮具", "深棕荔枝纹大容量水桶包", "279.00", "359.00", "taobao", "/alta/pieces/brown-bucket-bag.webp", "tote_bag", "#8A5230"),
                sim("sim-bg-5", "Verafied 风格", "哑光纯黑硬挺电脑通勤托特包", "399.00", null, "tmall", "/alta/pieces/black-tote.webp", "tote_bag", "#1E1E1E"),
                sim("sim-bg-6", "小众设计师", "极简纯白马鞍弧形腋下包", "259.00", "329.00", "jd", "/alta/pieces/white-saddle-bag.webp", "crossbody_bag", "#FFFFFF"),
                sim("sim-bg-7", "经典菱格纹", "黑色小羊皮菱格链条翻盖包", "499.00", "659.00", "jd", "/alta/pieces/black-quilted-bag.webp", "crossbody_bag", "#1E1E1E"),
                sim("sim-bg-8", "松弛感皮具", "黑色软皮慵懒大容量流浪包", "289.00", null, "taobao", "/alta/pieces/black-hobo-bag.webp", "tote_bag", "#262628")
        );
    }

    private static List<InspoDto.SimilarProductVo> outerwearSimilars() {
        return List.of(
                sim("sim-ow-1", "ICICLE 之禾风", "奶油白极简一粒扣微廓形西装", "699.00", "899.00", "tmall", "/alta/pieces/cream-blazer.webp", "blazer", "#F3EDE2"),
                sim("sim-ow-2", "MaxMara 风格平替", "驼色双面全羊毛系带长大衣", "899.00", "1299.00", "tmall", "/alta/pieces/camel-coat.webp", "trench", "#C7B299"),
                sim("sim-ow-3", "Massimo Dutti 风", "燕麦米色系带收腰羊毛大衣", "759.00", "990.00", "jd", "/alta/pieces/beige-belted-coat.webp", "trench", "#DFD3C3"),
                sim("sim-ow-4", "UR 都市通勤", "利落纯黑垂感廓形长款西装", "369.00", "459.00", "tmall", "/alta/pieces/black-blazer.webp", "blazer", "#1E1E1E"),
                sim("sim-ow-5", "COS 风格", "摩卡深棕双排扣长款风衣大衣", "599.00", null, "taobao", "/alta/pieces/brown-trench.webp", "trench", "#8A5230"),
                sim("sim-ow-6", "Reiss 男装风", "卡其棕双排扣羊毛硬挺大衣", "799.00", "1099.00", "jd", "/alta/pieces/khaki-double-coat.webp", "trench", "#8A5230"),
                sim("sim-ow-7", "美拉德机车风", "复古深棕立领皮质飞行员夹克", "329.00", "429.00", "taobao", "/alta/pieces/brown-bomber-jacket.webp", "suede_jacket", "#8A5230"),
                sim("sim-ow-8", "Clean Fit 基础款", "纯白短款重磅连帽拉链卫衣", "199.00", null, "jd", "/alta/pieces/white-zip-hoodie.webp", "cardigan", "#FFFFFF")
        );
    }

    private static List<InspoDto.SimilarProductVo> topSimilars() {
        return List.of(
                sim("sim-tp-1", "Ralph Lauren 风", "奶油白麻花羊绒混纺圆领毛衣", "359.00", "499.00", "tmall", "/alta/pieces/cream-polo-knit.webp", "cardigan", "#F3EDE2"),
                sim("sim-tp-2", "优衣库 U 严选", "高支纯白挺括长袖通勤衬衫", "199.00", null, "tmall", "/alta/pieces/white-shirt.webp", "tshirt", "#FFFFFF"),
                sim("sim-tp-3", "JNBY 风格", "奶白软糯绞花围裹感针织衫", "329.00", "429.00", "taobao", "/alta/pieces/cream-cable-sweater.webp", "cardigan", "#F3EDE2"),
                sim("sim-tp-4", "ZARA 同款", "美拉德深棕圆领粗针羊毛衫", "259.00", "299.00", "tmall", "/alta/pieces/brown-knit-sweater.webp", "knit_vest", "#8A5230"),
                sim("sim-tp-5", "静奢醋酸系列", "香槟燕麦缎面垂感长袖衬衫", "289.00", "369.00", "jd", "/alta/pieces/beige-satin-shirt.webp", "striped_shirt", "#DFD3C3"),
                sim("sim-tp-6", "Lisa Yang 风", "暖驼色全羊绒高领保暖针织衫", "459.00", "599.00", "tmall", "/alta/pieces/camel-turtleneck.webp", "knit_vest", "#C7B299"),
                sim("sim-tp-7", "极简基础内搭", "纯黑修身坑条半高领打底毛衣", "169.00", null, "jd", "/alta/pieces/black-turtleneck.webp", "knit_vest", "#1E1E1E"),
                sim("sim-tp-8", "老钱风男装", "浅驼色半拉链立领针织衫", "299.00", "389.00", "jd", "/alta/pieces/tan-quarter-zip.webp", "cardigan", "#C7B299")
        );
    }

    private static List<InspoDto.SimilarProductVo> bottomSimilars() {
        return List.of(
                sim("sim-bt-1", "极简高腰神裤", "黑色双褶垂感高腰阔腿西裤", "239.00", "319.00", "tmall", "/alta/pieces/black-pleated-pants.webp", "trousers", "#1E1E1E"),
                sim("sim-bt-2", "Clean Fit 丹宁", "奶油米白高腰直筒阔腿牛仔裤", "259.00", null, "taobao", "/alta/pieces/cream-wide-pants.webp", "jeans", "#F3EDE2"),
                sim("sim-bt-3", "复古水洗丹宁", "深蓝复古高腰直筒阔腿牛仔裤", "269.00", "349.00", "jd", "/alta/pieces/dark-blue-jeans.webp", "jeans", "#3B5372"),
                sim("sim-bt-4", "学院百褶短裙", "浅卡其高腰显腿长百褶半身裙", "169.00", "219.00", "taobao", "/alta/pieces/beige-pleated-skirt.webp", "pleated_skirt", "#DFD3C3"),
                sim("sim-bt-5", "醋酸缎面半裙", "橄榄绿高腰垂感鱼尾长款半身裙", "249.00", "329.00", "tmall", "/alta/pieces/olive-satin-skirt.webp", "pleated_skirt", "#5C634E"),
                sim("sim-bt-6", "都市通勤西裤", "藏青色双褶垂感宽腿西装裤", "279.00", null, "jd", "/alta/pieces/navy-wide-pants.webp", "trousers", "#2A364B"),
                sim("sim-bt-7", "Aritzia 同款", "纯黑高腰伞摆垂感长款半身裙", "259.00", "329.00", "taobao", "/alta/pieces/black-maxi-skirt.webp", "pleated_skirt", "#1E1E1E"),
                sim("sim-bt-8", "美拉德格纹裤", "复古千鸟格羊毛混纺阔腿裤", "319.00", "429.00", "tmall", "/alta/pieces/checked-wide-pants.webp", "trousers", "#8A5230")
        );
    }

    private static List<InspoDto.SimilarProductVo> shoeSimilars() {
        return List.of(
                sim("sim-sh-1", "VEJA 风格平替", "极简白黑尾真皮低帮板鞋德训鞋", "299.00", "399.00", "tmall", "/alta/pieces/veja-white-sneakers.webp", "sneakers", "#FFFFFF"),
                sim("sim-sh-2", "复古学院鞋履", "焦糖棕头层牛皮系带牛津乐福鞋", "369.00", "469.00", "jd", "/alta/pieces/tan-oxfords.webp", "loafers", "#8A5230"),
                sim("sim-sh-3", "Acne 风格", "极简纯黑方头软皮一脚蹬乐福鞋", "389.00", "499.00", "tmall", "/alta/pieces/black-loafers.webp", "loafers", "#1E1E1E"),
                sim("sim-sh-4", "骑士靴严选", "黑色真皮显腿直高筒不过膝骑士靴", "499.00", "699.00", "tmall", "/alta/pieces/black-knee-boots.webp", "loafers", "#1E1E1E"),
                sim("sim-sh-5", "秋日美拉德靴", "深棕色复古粗跟牛皮及膝长筒靴", "529.00", "689.00", "jd", "/alta/pieces/brown-tall-boots.webp", "loafers", "#8A5230"),
                sim("sim-sh-6", "UGG 风格", "栗棕色翻毛羊皮保暖厚底短靴", "359.00", "459.00", "jd", "/alta/pieces/brown-suede-boots.webp", "loafers", "#8A5230"),
                sim("sim-sh-7", "ASICS 复古跑鞋", "白银千禧风透气网面老爹运动鞋", "499.00", "590.00", "tmall", "/alta/pieces/asics-white-sneakers.webp", "sneakers", "#F5F5F5"),
                sim("sim-sh-8", "法式通勤单鞋", "深棕色尖头细跟真皮浅口高跟鞋", "329.00", null, "taobao", "/alta/pieces/brown-pumps.webp", "loafers", "#8A5230")
        );
    }

    private static InspoDto.LookPieceVo piece(String id, String brand, String name, String cat, String slotLabel,
                                              String color, String hex, String price, String imgUrl, String svgType,
                                              List<InspoDto.SimilarProductVo> sims) {
        return InspoDto.LookPieceVo.builder()
                .id(id)
                .brand(brand)
                .name(name)
                .category(cat)
                .slotLabel(slotLabel)
                .color(color)
                .colorHex(hex)
                .price(new BigDecimal(price))
                .imageUrl(imgUrl)
                .svgType(svgType)
                .taobaoUrl(taobaoUrl(brand + " " + name))
                .jdUrl(jdUrl(brand + " " + name))
                .similarItems(sims)
                .build();
    }

    private List<InspoDto.InspoLookVo> buildCatalog() {
        List<InspoDto.InspoLookVo> list = new ArrayList<>();

        // ===== 12 WOMENS ALTA STUDIO CUTOUT LOOKS (1:1 Outfit-Matched Segmented Pieces) =====
        list.add(InspoDto.InspoLookVo.builder()
                .id("look-w1").creatorName("苏菲").creatorHandle("@sophie_sh").city("上海 · 安福路")
                .gender("womens").title("深棕圆领毛衣叠穿白衬衫 · 学院长靴").styleTag("学院风").seasonTag("秋冬")
                .viewsText("14.2w").viewsCount(142000).rank(1).likesCount(1284).commentsCount(68).restylesCount(412)
                .modelImageUrl("/alta/looks/w1.webp")
                .fallbackSvgType("cardigan").styleTags(List.of("学院风", "美拉德复古", "极简通勤"))
                .pieces(List.of(
                        piece("w1-p1", "ZARA", "美拉德深棕圆领羊毛针织衫", "top", "上衣", "焦糖棕", "#8A5230", "299", "/alta/pieces/brown-knit-sweater.webp", "knit_vest", topSimilars()),
                        piece("w1-p2", "Brandy Melville", "纯白翻领长袖叠穿打底衬衫", "top", "衬衫", "纯白", "#FFFFFF", "199", "/alta/pieces/white-shirt.webp", "tshirt", topSimilars()),
                        piece("w1-p3", "Aritzia", "极简高腰黑色A字半身短裙", "bottom", "下装", "炭黑", "#1E1E1E", "329", "/alta/pieces/black-mini-skirt.webp", "pleated_skirt", bottomSimilars()),
                        piece("w1-p4", "Sam Edelman", "黑色真皮直筒不过膝骑士长靴", "shoes", "鞋履", "炭黑", "#1E1E1E", "699", "/alta/pieces/black-knee-boots.webp", "loafers", shoeSimilars()),
                        piece("w1-p5", "Songmont 风格", "深棕荔枝纹头层牛皮水桶包", "bag", "包袋", "焦糖棕", "#8A5230", "580", "/alta/pieces/brown-bucket-bag.webp", "tote_bag", bagSimilars()),
                        piece("w1-p6", "HEFANG", "925纯银极简几何素圈耳环", "accessory", "耳饰", "银色", "#C0C0C0", "269", "/alta/pieces/silver-hoop-earrings.webp", "crossbody_bag", earringSimilars())
                ))
                .sampleComments(List.of("白衬衫领口露出一截配深棕毛衣太显气质了！", "长靴和短裙比例绝佳，直接点进左边买了同款毛衣"))
                .build());

        list.add(InspoDto.InspoLookVo.builder()
                .id("look-w2").creatorName("温宁").creatorHandle("@wenning_bj").city("北京 · 三里屯")
                .gender("womens").title("奶油单排扣西装 × 顺色阔腿裤 · 老钱静奢").styleTag("极简通勤").seasonTag("春秋")
                .viewsText("11.6w").viewsCount(116000).rank(2).likesCount(986).commentsCount(47).restylesCount(356)
                .modelImageUrl("/alta/looks/w2.webp")
                .fallbackSvgType("blazer").styleTags(List.of("极简通勤", "Clean Fit", "干练面试"))
                .pieces(List.of(
                        piece("w2-p1", "ICICLE / Nanushka", "奶油白微廓形单排扣西装外套", "outerwear", "外套", "米白", "#F3EDE2", "899", "/alta/pieces/cream-blazer.webp", "blazer", outerwearSimilars()),
                        piece("w2-p2", "之禾精选", "香槟米白缎面垂感纽扣衬衫", "top", "上衣", "燕麦", "#DFD3C3", "399", "/alta/pieces/beige-satin-shirt.webp", "striped_shirt", topSimilars()),
                        piece("w2-p3", "TWP 同款", "奶油白高腰垂感直筒阔腿裤", "bottom", "下装", "米白", "#F3EDE2", "359", "/alta/pieces/cream-wide-pants.webp", "trousers", bottomSimilars()),
                        piece("w2-p4", "Demellier 风格", "奶油荔枝纹大容量通勤托特包", "bag", "包袋", "米白", "#F3EDE2", "680", "/alta/pieces/cream-tote.webp", "tote_bag", bagSimilars()),
                        piece("w2-p5", "CELINE 风格", "极简黑框椭圆复古太阳镜", "accessory", "墨镜", "炭黑", "#1E1E1E", "320", "/alta/pieces/celine-sunglasses.webp", "crossbody_bag", watchSimilars())
                ))
                .sampleComments(List.of("全套奶油白顺色太高级了，完全就是画廊主编感", "托特包配廓形西装上班气场满分"))
                .build());

        list.add(InspoDto.InspoLookVo.builder()
                .id("look-w3").creatorName("沈清梨").creatorHandle("@qingli_hz").city("杭州 · 天目里")
                .gender("womens").title("驼色长款羊毛大衣 × 羊绒高领 · 大女主").styleTag("美拉德复古").seasonTag("秋冬")
                .viewsText("10.8w").viewsCount(108000).rank(3).likesCount(874).commentsCount(52).restylesCount(298)
                .modelImageUrl("/alta/looks/w3.webp")
                .fallbackSvgType("trench").styleTags(List.of("极简通勤", "美拉德复古", "大女主"))
                .pieces(List.of(
                        piece("w3-p1", "ICICLE 之禾风", "经典驼色双面呢长款羊毛大衣", "outerwear", "外套", "卡其", "#C7B299", "1290", "/alta/pieces/camel-coat.webp", "trench", outerwearSimilars()),
                        piece("w3-p2", "鄂尔多斯 1980", "暖驼色全羊绒亲肤高领针织衫", "top", "上衣", "焦糖棕", "#8A5230", "599", "/alta/pieces/camel-turtleneck.webp", "knit_vest", topSimilars()),
                        piece("w3-p3", "Dries Van Noten 风", "复古美拉德细格纹羊毛阔腿裤", "bottom", "下装", "焦糖棕", "#8A5230", "399", "/alta/pieces/checked-wide-pants.webp", "trousers", bottomSimilars()),
                        piece("w3-p4", "思加图 STACCATO", "深棕色尖头细跟真皮通勤单鞋", "shoes", "鞋履", "焦糖棕", "#8A5230", "459", "/alta/pieces/brown-pumps.webp", "loafers", shoeSimilars())
                ))
                .sampleComments(List.of("天目里秋冬最爱的驼色大衣叠穿模板", "格纹阔腿裤中和了纯色大衣的单调"))
                .build());

        list.add(InspoDto.InspoLookVo.builder()
                .id("look-w4").creatorName("林知夏").creatorHandle("@zhixia_cd").city("成都 · 太古里")
                .gender("womens").title("米白绞花毛衣 × 橄榄绿缎面长裙 · 温柔呼吸感").styleTag("温柔韩系").seasonTag("春秋")
                .viewsText("9.4w").viewsCount(94000).rank(4).likesCount(792).commentsCount(38).restylesCount(265)
                .modelImageUrl("/alta/looks/w4.webp")
                .fallbackSvgType("cardigan").styleTags(List.of("温柔韩系", "约会", "法式松弛"))
                .pieces(List.of(
                        piece("w4-p1", "JNBY 江南布衣", "奶油米白立体绞花宽松毛衣", "top", "上衣", "米白", "#F3EDE2", "429", "/alta/pieces/cream-cable-sweater.webp", "cardigan", topSimilars()),
                        piece("w4-p2", "Edition 风格", "橄榄绿重磅醋酸缎面及踝半身裙", "bottom", "下装", "卡其", "#5C634E", "329", "/alta/pieces/olive-satin-skirt.webp", "pleated_skirt", bottomSimilars()),
                        piece("w4-p3", "百丽 BELLE", "黑色软皮粗跟及膝骑士长靴", "shoes", "鞋履", "炭黑", "#1E1E1E", "559", "/alta/pieces/black-knee-boots.webp", "loafers", shoeSimilars()),
                        piece("w4-p4", "Songmont", "黑色荔枝纹软皮慵懒单肩流浪包", "bag", "包袋", "炭黑", "#262628", "680", "/alta/pieces/black-hobo-bag.webp", "tote_bag", bagSimilars()),
                        piece("w4-p5", "LOLA ROSE", "银白小方盘精钢链条通勤女表", "accessory", "腕表", "银色", "#C0C0C0", "890", "/alta/pieces/silver-watch.webp", "crossbody_bag", watchSimilars())
                ))
                .sampleComments(List.of("粗针毛衣配丝滑缎面裙，材质碰撞太美了！"))
                .build());

        list.add(InspoDto.InspoLookVo.builder()
                .id("look-w5").creatorName("周书意").creatorHandle("@shuyi_wh").city("武汉 · 黎黄陂路")
                .gender("womens").title("纯白V领针织 × 焦糖郁金香短裙 · 甜酷千金").styleTag("法式松弛").seasonTag("春秋")
                .viewsText("8.6w").viewsCount(86000).rank(5).likesCount(735).commentsCount(33).restylesCount(241)
                .modelImageUrl("/alta/looks/w5.webp")
                .fallbackSvgType("cardigan").styleTags(List.of("温柔韩系", "约会", "Clean Fit"))
                .pieces(List.of(
                        piece("w5-p1", "URBAN REVIVO", "纯净奶白宽松V领软糯针织衫", "top", "上衣", "纯白", "#FFFFFF", "239", "/alta/pieces/white-vneck-sweater.webp", "cardigan", topSimilars()),
                        piece("w5-p2", "Moussy 风格", "焦糖棕高腰郁金香花苞半身短裙", "bottom", "下装", "焦糖棕", "#8A5230", "219", "/alta/pieces/brown-tulip-skirt.webp", "pleated_skirt", bottomSimilars()),
                        piece("w5-p3", "UGG 同款", "栗棕色翻毛皮厚底保暖踝靴", "shoes", "鞋履", "焦糖棕", "#8A5230", "399", "/alta/pieces/brown-suede-boots.webp", "loafers", shoeSimilars()),
                        piece("w5-p4", "小香风精选", "黑色菱格纹羊皮链条翻盖包", "bag", "包袋", "炭黑", "#1E1E1E", "520", "/alta/pieces/black-quilted-bag.webp", "crossbody_bag", bagSimilars()),
                        piece("w5-p5", "HEFANG 何方", "法式几何镀金圆环耳圈", "accessory", "耳饰", "金色", "#D4AF37", "268", "/alta/pieces/gold-hoop-earrings.webp", "crossbody_bag", earringSimilars())
                ))
                .sampleComments(List.of("V领显脸小，配棕色短裙和短靴周末约会绝配"))
                .build());

        list.add(InspoDto.InspoLookVo.builder()
                .id("look-w6").creatorName("陈星若").creatorHandle("@xingruo_sz").city("深圳 · 万象天地")
                .gender("womens").title("白衬衫 × 藏青阔腿裤 × 丝巾点睛 · 智感通勤").styleTag("极简通勤").seasonTag("春夏秋")
                .viewsText("7.9w").viewsCount(79000).rank(6).likesCount(680).commentsCount(29).restylesCount(218)
                .modelImageUrl("/alta/looks/w6.webp")
                .fallbackSvgType("striped_shirt").styleTags(List.of("极简通勤", "法式松弛", "干练面试"))
                .pieces(List.of(
                        piece("w6-p1", "Theory 风格", "挺括纯白廓形长袖通勤衬衫", "top", "上衣", "纯白", "#FFFFFF", "259", "/alta/pieces/white-shirt.webp", "tshirt", topSimilars()),
                        piece("w6-p2", "Massimo Dutti", "藏青色高腰双褶垂感阔腿西裤", "bottom", "下装", "牛仔蓝", "#2A364B", "399", "/alta/pieces/navy-wide-pants.webp", "trousers", bottomSimilars()),
                        piece("w6-p3", "法式桑蚕丝", "红白蓝航海风撞色真丝领巾", "accessory", "丝巾", "红色", "#9E2A2B", "159", "/alta/pieces/silk-scarf.webp", "crossbody_bag", watchSimilars()),
                        piece("w6-p4", "Bershka 精选", "复古酒红翻毛麂皮单肩腋下包", "bag", "包袋", "红色", "#9E2A2B", "279", "/alta/pieces/red-suede-bag.webp", "crossbody_bag", bagSimilars())
                ))
                .sampleComments(List.of("红蓝丝巾和酒红腋下包把白衬衫黑蓝西裤一下子点活了！"))
                .build());

        list.add(InspoDto.InspoLookVo.builder()
                .id("look-w7").creatorName("许清欢").creatorHandle("@qinghuan_sh").city("上海 · 武康路")
                .gender("womens").title("燕麦针织连衣裙 × 披肩叠搭 · 画廊千金感").styleTag("温柔韩系").seasonTag("春秋")
                .viewsText("7.5w").viewsCount(75000).rank(7).likesCount(645).commentsCount(31).restylesCount(196)
                .modelImageUrl("/alta/looks/w7.webp")
                .fallbackSvgType("cardigan").styleTags(List.of("温柔韩系", "极简通勤", "约会"))
                .pieces(List.of(
                        piece("w7-p1", "COS 风格", "浅燕麦修身坑条针织连衣短裙", "top", "连衣裙", "燕麦", "#DFD3C3", "359", "/alta/pieces/beige-knit-dress.webp", "cardigan", topSimilars()),
                        piece("w7-p2", "叠穿针织披肩", "摩卡棕软糯羊毛搭肩针织披肩", "outerwear", "披肩", "焦糖棕", "#8A5230", "189", "/alta/pieces/sweater-shawl.webp", "cardigan", outerwearSimilars()),
                        piece("w7-p3", "Coccinelle 同款", "灰棕麂皮新月弧形单肩腋下包", "bag", "包袋", "燕麦", "#C7B299", "560", "/alta/pieces/taupe-crescent-bag.webp", "crossbody_bag", bagSimilars()),
                        piece("w7-p4", "绒面长靴", "灰棕翻毛皮显瘦不过膝长筒靴", "shoes", "鞋履", "卡其", "#9E9589", "499", "/alta/pieces/taupe-suede-boots.webp", "loafers", shoeSimilars()),
                        piece("w7-p5", "四叶草轻奢", "18K镀金白贝母四叶草锁骨链", "accessory", "项链", "金色", "#D4AF37", "299", "/alta/pieces/gold-clover-necklace.webp", "crossbody_bag", earringSimilars())
                ))
                .sampleComments(List.of("针织裙外搭同色系披肩层次感满分"))
                .build());

        list.add(InspoDto.InspoLookVo.builder()
                .id("look-w8").creatorName("顾清音").creatorHandle("@qingyin_nj").city("南京 · 颐和路")
                .gender("womens").title("燕麦系带羊毛大衣 × 同色百褶裙 · 温柔秋日").styleTag("美拉德复古").seasonTag("秋冬")
                .viewsText("7.1w").viewsCount(71000).rank(8).likesCount(612).commentsCount(27).restylesCount(184)
                .modelImageUrl("/alta/looks/w8.webp")
                .fallbackSvgType("trench").styleTags(List.of("温柔韩系", "美拉德复古", "极简通勤"))
                .pieces(List.of(
                        piece("w8-p1", "ANEWSTA / 之禾风", "燕麦米色系带收腰长款羊毛大衣", "outerwear", "外套", "燕麦", "#DFD3C3", "899", "/alta/pieces/beige-belted-coat.webp", "trench", outerwearSimilars()),
                        piece("w8-p2", "极简羊绒", "浅燕麦半高领修身打底针织衫", "top", "上衣", "燕麦", "#DFD3C3", "259", "/alta/pieces/beige-mock-sweater.webp", "knit_vest", topSimilars()),
                        piece("w8-p3", "学院高腰裙", "燕麦杏色防走光高腰百褶短裙", "bottom", "下装", "卡其", "#C7B299", "199", "/alta/pieces/beige-pleated-skirt.webp", "pleated_skirt", bottomSimilars()),
                        piece("w8-p4", "Miu Miu 风格", "焦糖棕复古油蜡皮单肩腋下包", "bag", "包袋", "焦糖棕", "#8A5230", "459", "/alta/pieces/brown-shoulder-bag.webp", "crossbody_bag", bagSimilars()),
                        piece("w8-p5", "Coach 风格", "深棕头层牛皮粗跟高筒骑士靴", "shoes", "鞋履", "焦糖棕", "#8A5230", "599", "/alta/pieces/brown-tall-boots.webp", "loafers", shoeSimilars())
                ))
                .sampleComments(List.of("燕麦配焦糖棕真的是秋冬永远的神"))
                .build());

        list.add(InspoDto.InspoLookVo.builder()
                .id("look-w9").creatorName("唐语嫣").creatorHandle("@yuyan_gz").city("广州 · 东山口")
                .gender("womens").title("麻花毛衣叠穿白衬衫 × 黑色长裙 · 知识分子风").styleTag("知识分子风").seasonTag("秋冬")
                .viewsText("6.8w").viewsCount(68000).rank(9).likesCount(578).commentsCount(25).restylesCount(172)
                .modelImageUrl("/alta/looks/w9.webp")
                .fallbackSvgType("cardigan").styleTags(List.of("极简通勤", "学院风", "Clean Fit"))
                .pieces(List.of(
                        piece("w9-p1", "Polo Ralph Lauren", "奶油白绞花羊毛羊绒圆领毛衣", "top", "毛衣", "米白", "#F3EDE2", "499", "/alta/pieces/cream-polo-knit.webp", "cardigan", topSimilars()),
                        piece("w9-p2", "优衣库", "垂感纯白抗皱长袖通勤衬衫", "top", "衬衫", "纯白", "#FFFFFF", "199", "/alta/pieces/white-blouse.webp", "tshirt", topSimilars()),
                        piece("w9-p3", "Aritzia", "纯黑高腰垂感A字及踝半身长裙", "bottom", "下装", "炭黑", "#1E1E1E", "319", "/alta/pieces/black-maxi-skirt.webp", "pleated_skirt", bottomSimilars()),
                        piece("w9-p4", "Verafied New York", "哑光黑金扣书本托特通勤大包", "bag", "包袋", "炭黑", "#1E1E1E", "499", "/alta/pieces/black-tote.webp", "tote_bag", bagSimilars()),
                        piece("w9-p5", "经典通勤表", "银盘精钢链带日历石英女表", "accessory", "腕表", "银色", "#C0C0C0", "799", "/alta/pieces/silver-watch.webp", "crossbody_bag", watchSimilars())
                ))
                .sampleComments(List.of("干净克制的书卷气，上班和去美术馆都很合适"))
                .build());

        list.add(InspoDto.InspoLookVo.builder()
                .id("look-w10").creatorName("宋清欢").creatorHandle("@song_sh").city("上海 · 静安嘉里")
                .gender("womens").title("纯黑廓形长西装 × 焦糖半裙 · 冷感摩登").styleTag("干练面试").seasonTag("春秋")
                .viewsText("6.4w").viewsCount(64000).rank(10).likesCount(534).commentsCount(22).restylesCount(158)
                .modelImageUrl("/alta/looks/w10.webp")
                .fallbackSvgType("blazer").styleTags(List.of("干练面试", "极简通勤"))
                .pieces(List.of(
                        piece("w10-p1", "Tory Burch 风格", "纯黑精纺垂感长款收腰西装外套", "outerwear", "外套", "炭黑", "#1E1E1E", "599", "/alta/pieces/black-blazer.webp", "blazer", outerwearSimilars()),
                        piece("w10-p2", "COS 基础款", "纯黑修身半高领羊毛针织打底", "top", "上衣", "炭黑", "#1E1E1E", "229", "/alta/pieces/black-turtleneck.webp", "knit_vest", topSimilars()),
                        piece("w10-p3", "UR 摩登", "焦糖棕金属扣高腰A字短裙", "bottom", "下装", "焦糖棕", "#8A5230", "219", "/alta/pieces/brown-tulip-skirt.webp", "pleated_skirt", bottomSimilars()),
                        piece("w10-p4", "真皮骑士靴", "黑色尖头修腿高筒长靴", "shoes", "鞋履", "炭黑", "#1E1E1E", "559", "/alta/pieces/black-knee-boots.webp", "loafers", shoeSimilars())
                ))
                .sampleComments(List.of("黑西装配一点焦糖棕立刻不沉闷了"))
                .build());

        list.add(InspoDto.InspoLookVo.builder()
                .id("look-w11").creatorName("程嘉").creatorHandle("@chengjia_bj").city("北京 · 国贸")
                .gender("womens").title("深棕长款大衣 × 白衬衫 × 皮质半裙 · 都会精英").styleTag("极简通勤").seasonTag("秋冬")
                .viewsText("6.0w").viewsCount(60000).rank(11).likesCount(489).commentsCount(19).restylesCount(145)
                .modelImageUrl("/alta/looks/w11.webp")
                .fallbackSvgType("trench").styleTags(List.of("极简通勤", "美拉德复古", "干练面试"))
                .pieces(List.of(
                        piece("w11-p1", "摩卡羊毛呢", "深棕色直筒过膝双面呢大衣", "outerwear", "外套", "焦糖棕", "#8A5230", "980", "/alta/pieces/brown-wool-coat.webp", "trench", outerwearSimilars()),
                        piece("w11-p2", "通勤白衬衫", "纯白挺括翻领长袖打底衬衫", "top", "上衣", "纯白", "#FFFFFF", "199", "/alta/pieces/white-shirt.webp", "tshirt", topSimilars()),
                        piece("w11-p3", "复古中长裙", "深棕哑光皮质高腰开叉迷笛裙", "bottom", "下装", "焦糖棕", "#8A5230", "289", "/alta/pieces/brown-midi-skirt.webp", "pleated_skirt", bottomSimilars()),
                        piece("w11-p4", "极简黑托特", "黑色硬挺牛皮通勤大容量托特包", "bag", "包袋", "炭黑", "#1E1E1E", "459", "/alta/pieces/black-tote.webp", "tote_bag", bagSimilars()),
                        piece("w11-p5", "LOLA ROSE", "复古小金表方形石英女表", "accessory", "腕表", "金色", "#D4AF37", "980", "/alta/pieces/gold-watch.webp", "crossbody_bag", watchSimilars())
                ))
                .sampleComments(List.of("国贸通勤标准答案，气场和质感都在线"))
                .build());

        list.add(InspoDto.InspoLookVo.builder()
                .id("look-w12").creatorName("陆然").creatorHandle("@luran_xm").city("厦门 · 沙坡尾")
                .gender("womens").title("摩卡印花短T × 竖条纹阔腿裤 · 松弛周末").styleTag("Clean Fit").seasonTag("春夏秋")
                .viewsText("5.6w").viewsCount(56000).rank(12).likesCount(442).commentsCount(17).restylesCount(132)
                .modelImageUrl("/alta/looks/w12.webp")
                .fallbackSvgType("tshirt").styleTags(List.of("Clean Fit", "日常休闲"))
                .pieces(List.of(
                        piece("w12-p1", "复古美式短T", "咖色复古印花重磅纯棉短袖T恤", "top", "上衣", "焦糖棕", "#8A5230", "149", "/alta/pieces/brown-graphic-tee.webp", "tshirt", topSimilars()),
                        piece("w12-p2", "Delave 同款", "棕米撞色竖条纹宽松弯刀阔腿裤", "bottom", "下装", "燕麦", "#DFD3C3", "259", "/alta/pieces/striped-balloon-pants.webp", "trousers", bottomSimilars()),
                        piece("w12-p3", "ASICS 亚瑟士", "白银拼色透气网面复古运动跑鞋", "shoes", "鞋履", "纯白", "#FFFFFF", "529", "/alta/pieces/asics-white-sneakers.webp", "sneakers", shoeSimilars())
                ))
                .sampleComments(List.of("条纹弯刀裤巨显腿长，周末逛街暴走毫无压力"))
                .build());

        // ===== 6 MENS ALTA STUDIO CUTOUT LOOKS (1:1 Outfit-Matched Segmented Pieces) =====
        list.add(InspoDto.InspoLookVo.builder()
                .id("look-m1").creatorName("周予安").creatorHandle("@yuan_sh").city("上海 · 巨鹿路")
                .gender("mens").title("卡其双排扣羊毛大衣 × 绞花毛衣 · 雅痞绅装").styleTag("美拉德复古").seasonTag("秋冬")
                .viewsText("11.9w").viewsCount(119000).rank(1).likesCount(940).commentsCount(44).restylesCount(274)
                .modelImageUrl("/alta/looks/m1.webp")
                .fallbackSvgType("trench").styleTags(List.of("美拉德复古", "极简通勤", "Clean Fit"))
                .pieces(List.of(
                        piece("m1-p1", "Reiss 风格", "卡其棕双排扣硬挺长款羊毛大衣", "outerwear", "外套", "卡其", "#8A5230", "990", "/alta/pieces/khaki-double-coat.webp", "trench", outerwearSimilars()),
                        piece("m1-p2", "Aquascutum 风", "暖咖色纯羊毛经典绞花圆领毛衣", "top", "上衣", "焦糖棕", "#8A5230", "459", "/alta/pieces/brown-cable-sweater.webp", "cardigan", topSimilars()),
                        piece("m1-p3", "Nudie 风格", "炭黑色修身直筒水洗丹宁牛仔裤", "bottom", "下装", "炭黑", "#262628", "359", "/alta/pieces/black-wide-jeans.webp", "jeans", bottomSimilars()),
                        piece("m1-p4", "Duke + Dexter 风", "黑色头层牛皮系带德比皮鞋", "shoes", "鞋履", "炭黑", "#1E1E1E", "569", "/alta/pieces/mens-black-shoes.webp", "loafers", shoeSimilars()),
                        piece("m1-p5", "经典黑金腕表", "黑金表盘精钢商务男士腕表", "accessory", "腕表", "金色", "#D4AF37", "790", "/alta/pieces/black-gold-watch.webp", "crossbody_bag", watchSimilars())
                ))
                .sampleComments(List.of("男生秋冬这样穿质感拉满，清爽不油腻！"))
                .build());

        list.add(InspoDto.InspoLookVo.builder()
                .id("look-m2").creatorName("陆景行").creatorHandle("@jingxing_bj").city("北京 · 国贸")
                .gender("mens").title("纯黑双排扣西装 × 条纹阔腿西裤 · 建筑感极简").styleTag("极简通勤").seasonTag("四季")
                .viewsText("10.2w").viewsCount(102000).rank(2).likesCount(812).commentsCount(39).restylesCount(231)
                .modelImageUrl("/alta/looks/m2.webp")
                .fallbackSvgType("blazer").styleTags(List.of("极简通勤", "干练面试"))
                .pieces(List.of(
                        piece("m2-p1", "COS 男装", "纯黑精纺羊毛双排扣廓形西装", "outerwear", "外套", "炭黑", "#1E1E1E", "1190", "/alta/pieces/mens-black-blazer.webp", "blazer", outerwearSimilars()),
                        piece("m2-p2", "修身黑衬衫", "纯黑抗皱免烫修身长袖男装衬衫", "top", "上衣", "炭黑", "#1E1E1E", "259", "/alta/pieces/black-slim-shirt.webp", "tshirt", topSimilars()),
                        piece("m2-p3", "BV 风格剪裁", "暗细条纹垂感高支羊毛阔腿西裤", "bottom", "下装", "炭黑", "#262628", "499", "/alta/pieces/mens-pinstripe-pants.webp", "trousers", bottomSimilars()),
                        piece("m2-p4", "Acne Studios 风", "极简纯黑方头厚底真皮乐福鞋", "shoes", "鞋履", "炭黑", "#1E1E1E", "529", "/alta/pieces/black-loafers.webp", "loafers", shoeSimilars())
                ))
                .sampleComments(List.of("全黑西装把肩线和裤型穿出来太高级了"))
                .build());

        list.add(InspoDto.InspoLookVo.builder()
                .id("look-m3").creatorName("林澈").creatorHandle("@linche_hz").city("杭州 · 湖滨")
                .gender("mens").title("黑色长大衣 × 深灰圆领毛衣 × 小白鞋 · 韩系松弛").styleTag("Clean Fit").seasonTag("秋冬")
                .viewsText("8.7w").viewsCount(87000).rank(3).likesCount(695).commentsCount(28).restylesCount(198)
                .modelImageUrl("/alta/looks/m3.webp")
                .fallbackSvgType("trench").styleTags(List.of("Clean Fit", "日常休闲", "极简通勤"))
                .pieces(List.of(
                        piece("m3-p1", "ZARA 男装", "纯黑双排扣过膝廓形羊毛大衣", "outerwear", "外套", "炭黑", "#1E1E1E", "899", "/alta/pieces/mens-black-coat.webp", "trench", outerwearSimilars()),
                        piece("m3-p2", "基础羊毛衫", "炭灰色圆领落肩针织打底毛衣", "top", "上衣", "炭黑", "#38383A", "269", "/alta/pieces/dark-grey-sweater.webp", "knit_vest", topSimilars()),
                        piece("m3-p3", "Bershka 男装", "水洗炭黑直筒宽腿丹宁牛仔裤", "bottom", "下装", "炭黑", "#262628", "299", "/alta/pieces/black-wide-jeans.webp", "jeans", bottomSimilars()),
                        piece("m3-p4", "VEJA 同款", "黑白尾真皮极简低帮板鞋", "shoes", "鞋履", "纯白", "#FFFFFF", "369", "/alta/pieces/veja-white-sneakers.webp", "sneakers", shoeSimilars()),
                        piece("m3-p5", "SEIKO 精工", "银壳黑盘自动机械商务腕表", "accessory", "腕表", "银色", "#C0C0C0", "1180", "/alta/pieces/seiko-watch.webp", "crossbody_bag", watchSimilars())
                ))
                .sampleComments(List.of("黑大衣配白板鞋干净清爽，完全不显老气"))
                .build());

        list.add(InspoDto.InspoLookVo.builder()
                .id("look-m4").creatorName("沈砚").creatorHandle("@shenyan_cd").city("成都 · 东郊记忆")
                .gender("mens").title("水洗蓝衬衫 × 橄榄工装裤 × 托特包 · 日系盐系").styleTag("Clean Fit").seasonTag("春秋")
                .viewsText("7.5w").viewsCount(75000).rank(4).likesCount(560).commentsCount(21).restylesCount(160)
                .modelImageUrl("/alta/looks/m4.webp")
                .fallbackSvgType("striped_shirt").styleTags(List.of("Clean Fit", "日常休闲"))
                .pieces(List.of(
                        piece("m4-p1", "A&F 风格", "浅水洗牛津纺宽松长袖休闲衬衫", "top", "上衣", "牛仔蓝", "#6B8CAE", "239", "/alta/pieces/blue-chambray-shirt.webp", "striped_shirt", topSimilars()),
                        piece("m4-p2", "优衣库 U 男装", "橄榄绿纯棉斜纹直筒休闲长裤", "bottom", "下装", "卡其", "#5C634E", "249", "/alta/pieces/olive-cotton-pants.webp", "trousers", bottomSimilars()),
                        piece("m4-p3", "VEJA 复古", "军绿翻毛皮拼接复古慢跑德训鞋", "shoes", "鞋履", "燕麦", "#5C634E", "389", "/alta/pieces/veja-suede-sneakers.webp", "sneakers", shoeSimilars()),
                        piece("m4-p4", "极简男包", "纯黑头层牛皮竖款大容量托特包", "bag", "包袋", "炭黑", "#1E1E1E", "459", "/alta/pieces/mens-black-tote.webp", "tote_bag", bagSimilars())
                ))
                .sampleComments(List.of("浅蓝衬衫配军绿裤子太舒服耐看了"))
                .build());

        list.add(InspoDto.InspoLookVo.builder()
                .id("look-m5").creatorName("顾深").creatorHandle("@gushen_sh").city("上海 · 西岸美术馆")
                .gender("mens").title("棕色飞行员皮衣叠穿白卫衣 × 阔腿丹宁 · 街头混搭").styleTag("美拉德复古").seasonTag("春秋")
                .viewsText("6.9w").viewsCount(69000).rank(5).likesCount(498).commentsCount(19).restylesCount(142)
                .modelImageUrl("/alta/looks/m5.webp")
                .fallbackSvgType("suede_jacket").styleTags(List.of("美拉德复古", "Clean Fit", "日常休闲"))
                .pieces(List.of(
                        piece("m5-p1", "复古机车夹克", "深棕色哑光皮质宽松飞行员夹克", "outerwear", "外套", "焦糖棕", "#8A5230", "399", "/alta/pieces/brown-bomber-jacket.webp", "suede_jacket", outerwearSimilars()),
                        piece("m5-p2", "重磅卫衣开衫", "纯白短款箱型连帽拉链卫衣", "top", "内搭", "纯白", "#FFFFFF", "199", "/alta/pieces/white-zip-hoodie.webp", "cardigan", outerwearSimilars()),
                        piece("m5-p3", "美式复古丹宁", "浅蓝水洗高街宽松阔腿牛仔裤", "bottom", "下装", "牛仔蓝", "#6B8CAE", "279", "/alta/pieces/blue-wide-jeans.webp", "jeans", bottomSimilars()),
                        piece("m5-p4", "NIKE 经典", "纯白荔枝皮低帮空军一号板鞋", "shoes", "鞋履", "纯白", "#FFFFFF", "599", "/alta/pieces/nike-white-sneakers.webp", "sneakers", shoeSimilars())
                ))
                .sampleComments(List.of("棕皮衣内搭白连帽卫衣层次感绝了！"))
                .build());

        list.add(InspoDto.InspoLookVo.builder()
                .id("look-m6").creatorName("季明").creatorHandle("@jiming_sz").city("深圳 · 后海")
                .gender("mens").title("浅驼半拉链毛衣 × 藏青西裤 · 轻熟老钱风").styleTag("极简通勤").seasonTag("春秋")
                .viewsText("6.1w").viewsCount(61000).rank(6).likesCount(420).commentsCount(16).restylesCount(118)
                .modelImageUrl("/alta/looks/m6.webp")
                .fallbackSvgType("cardigan").styleTags(List.of("极简通勤", "干练面试", "学院风"))
                .pieces(List.of(
                        piece("m6-p1", "Polo Ralph Lauren", "浅驼色精纺羊毛半拉链立领针织衫", "top", "上衣", "卡其", "#C7B299", "499", "/alta/pieces/tan-quarter-zip.webp", "cardigan", topSimilars()),
                        piece("m6-p2", "商务修身西裤", "藏青色肌理感抗皱垂感休闲西裤", "bottom", "下装", "牛仔蓝", "#2A364B", "329", "/alta/pieces/mens-navy-pants.webp", "trousers", bottomSimilars()),
                        piece("m6-p3", "复古牛津皮鞋", "浅棕擦色真皮系带商务休闲皮鞋", "shoes", "鞋履", "焦糖棕", "#8A5230", "469", "/alta/pieces/tan-oxfords.webp", "loafers", shoeSimilars()),
                        piece("m6-p4", "极简方框墨镜", "黑色板材几何框防紫外线太阳镜", "accessory", "墨镜", "炭黑", "#1E1E1E", "259", "/alta/pieces/celine-sunglasses.webp", "crossbody_bag", watchSimilars())
                ))
                .sampleComments(List.of("驼色半拉链配藏青西裤，体面又不紧绷"))
                .build());

        return list;
    }
}
