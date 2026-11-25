package com.wardrobe.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wardrobe.common.BusinessException;
import com.wardrobe.common.ErrorCode;
import com.wardrobe.common.JsonUtils;
import com.wardrobe.mapper.*;
import com.wardrobe.model.dto.AuthDto;
import com.wardrobe.model.entity.*;
import com.wardrobe.security.JwtUtil;
import com.wardrobe.security.PasswordUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final SysUserMapper userMapper;
    private final UserPreferenceMapper preferenceMapper;
    private final WardrobeItemMapper wardrobeItemMapper;
    private final OutfitPlanMapper outfitPlanMapper;
    private final OutfitHistoryMapper outfitHistoryMapper;
    private final PasswordUtil passwordUtil;
    private final JwtUtil jwtUtil;
    private final JsonUtils jsonUtils;

    @Transactional
    public AuthDto.AuthResponse register(AuthDto.RegisterRequest req) {
        String username = req.getUsername().trim();
        Long existing = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (existing != null && existing > 0) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setNickname(req.getNickname().trim());
        user.setPassword(passwordUtil.encode(req.getPassword()));
        user.setGender(0);
        user.setStatus(1);
        user.setLastLoginAt(LocalDateTime.now());
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);

        UserPreference pref = new UserPreference();
        pref.setUserId(user.getId());
        pref.setDefaultCity(req.getDefaultCity() != null && !req.getDefaultCity().isBlank() ? req.getDefaultCity().trim() : "武汉");
        pref.setFavoriteStyles(jsonUtils.toJson(List.of("极简通勤", "Clean Fit")));
        pref.setFavoriteColors(jsonUtils.toJson(List.of("米白", "燕麦", "牛仔蓝")));
        pref.setDislikedColors(jsonUtils.toJson(List.of("荧光绿")));
        pref.setPreferredScenes(jsonUtils.toJson(List.of("职场通勤", "日常休闲")));
        pref.setBudgetLimit(new BigDecimal("300.00"));
        pref.setStyleWeights(jsonUtils.toJson(Map.of("极简通勤", 0.85, "Clean Fit", 0.80)));
        pref.setUpdatedAt(LocalDateTime.now());
        preferenceMapper.insert(pref);

        if (Boolean.TRUE.equals(req.getLoadStarterPack())) {
            insertStarterWardrobe(user.getId());
        }

        return buildAuthResponse(user);
    }

    public AuthDto.AuthResponse login(AuthDto.LoginRequest req) {
        String username = req.getUsername().trim();
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user == null || !passwordUtil.matches(req.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.BAD_CREDENTIALS);
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该账号已被禁用");
        }
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(user);
        return buildAuthResponse(user);
    }

    public AuthDto.UserInfoVo getCurrentUserInfo(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return toUserInfoVo(user);
    }

    private AuthDto.AuthResponse buildAuthResponse(SysUser user) {
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        return AuthDto.AuthResponse.builder()
                .token(token)
                .expiresIn(jwtUtil.getExpirationSeconds())
                .userInfo(toUserInfoVo(user))
                .build();
    }

    private AuthDto.UserInfoVo toUserInfoVo(SysUser user) {
        Long wardrobeCount = wardrobeItemMapper.selectCount(
                new LambdaQueryWrapper<WardrobeItem>().eq(WardrobeItem::getUserId, user.getId()));
        Long historyCount = outfitHistoryMapper.selectCount(
                new LambdaQueryWrapper<OutfitHistory>().eq(OutfitHistory::getUserId, user.getId()));
        Long favCount = outfitPlanMapper.selectCount(
                new LambdaQueryWrapper<OutfitPlan>()
                        .eq(OutfitPlan::getUserId, user.getId())
                        .eq(OutfitPlan::getIsFavorite, 1));
        return AuthDto.UserInfoVo.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .email(user.getEmail())
                .wardrobeCount(wardrobeCount != null ? wardrobeCount : 0L)
                .historyCount(historyCount != null ? historyCount : 0L)
                .favoriteCount(favCount != null ? favCount : 0L)
                .build();
    }

    private void insertStarterWardrobe(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        WardrobeItem tee = new WardrobeItem();
        tee.setUserId(userId);
        tee.setName("基础重磅白T");
        tee.setCategory("top");
        tee.setSubCategory("短袖T恤");
        tee.setColor("纯白");
        tee.setColorHex("#FFFFFF");
        tee.setSeason("spring,summer,autumn");
        tee.setThickness(1);
        tee.setStyleTags(jsonUtils.toJson(List.of("Clean Fit", "百搭")));
        tee.setMaterial("260g精梳纯棉");
        tee.setSvgType("tshirt");
        tee.setProcessedImageUrl("svg://tshirt");
        tee.setSourceType("manual");
        tee.setIsManuallyCorrected(0);
        tee.setPrice(new BigDecimal("129.00"));
        tee.setWearCount(1);
        tee.setCreatedAt(now);
        tee.setUpdatedAt(now);
        wardrobeItemMapper.insert(tee);

        WardrobeItem jeans = new WardrobeItem();
        jeans.setUserId(userId);
        jeans.setName("深蓝直筒牛仔裤");
        jeans.setCategory("bottom");
        jeans.setSubCategory("直筒牛仔裤");
        jeans.setColor("牛仔蓝");
        jeans.setColorHex("#3B5372");
        jeans.setSeason("spring,summer,autumn,winter");
        jeans.setThickness(2);
        jeans.setStyleTags(jsonUtils.toJson(List.of("日常休闲", "百搭")));
        jeans.setMaterial("全棉丹宁");
        jeans.setSvgType("jeans");
        jeans.setProcessedImageUrl("svg://jeans");
        jeans.setSourceType("manual");
        jeans.setIsManuallyCorrected(0);
        jeans.setPrice(new BigDecimal("359.00"));
        jeans.setWearCount(1);
        jeans.setCreatedAt(now);
        jeans.setUpdatedAt(now);
        wardrobeItemMapper.insert(jeans);

        WardrobeItem sneakers = new WardrobeItem();
        sneakers.setUserId(userId);
        sneakers.setName("复古米灰德训鞋");
        sneakers.setCategory("shoes");
        sneakers.setSubCategory("德训鞋");
        sneakers.setColor("燕麦");
        sneakers.setColorHex("#DFD3C3");
        sneakers.setSeason("spring,summer,autumn,winter");
        sneakers.setThickness(2);
        sneakers.setStyleTags(jsonUtils.toJson(List.of("Clean Fit")));
        sneakers.setMaterial("翻毛皮拼接");
        sneakers.setSvgType("sneakers");
        sneakers.setProcessedImageUrl("svg://sneakers");
        sneakers.setSourceType("manual");
        sneakers.setIsManuallyCorrected(0);
        sneakers.setPrice(new BigDecimal("429.00"));
        sneakers.setWearCount(1);
        sneakers.setCreatedAt(now);
        sneakers.setUpdatedAt(now);
        wardrobeItemMapper.insert(sneakers);
    }
}
