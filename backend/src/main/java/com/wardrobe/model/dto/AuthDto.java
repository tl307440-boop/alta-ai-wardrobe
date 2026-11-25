package com.wardrobe.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

public class AuthDto {

    @Data
    public static class RegisterRequest {
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 32, message = "用户名长度需为 3~32 位")
        private String username;

        @NotBlank(message = "昵称不能为空")
        @Size(min = 1, max = 30, message = "昵称长度不能超过 30 位")
        private String nickname;

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 64, message = "密码至少需要 6 位字符")
        private String password;

        private String defaultCity = "武汉";
        private Boolean loadStarterPack = true;
    }

    @Data
    public static class LoginRequest {
        @NotBlank(message = "请输入用户名")
        private String username;

        @NotBlank(message = "请输入密码")
        private String password;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfoVo {
        private Long id;
        private String username;
        private String nickname;
        private String avatarUrl;
        private String email;
        private long wardrobeCount;
        private long historyCount;
        private long favoriteCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthResponse {
        private String token;
        private long expiresIn;
        private UserInfoVo userInfo;
    }
}
