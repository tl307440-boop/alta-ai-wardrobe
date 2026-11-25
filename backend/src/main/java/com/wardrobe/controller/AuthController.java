package com.wardrobe.controller;

import com.wardrobe.common.Result;
import com.wardrobe.model.dto.AuthDto;
import com.wardrobe.security.UserContextHolder;
import com.wardrobe.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/register")
    public Result<AuthDto.AuthResponse> register(@Valid @RequestBody AuthDto.RegisterRequest req) {
        return Result.ok(authService.register(req));
    }

    @PostMapping("/login")
    public Result<AuthDto.AuthResponse> login(@Valid @RequestBody AuthDto.LoginRequest req) {
        return Result.ok(authService.login(req));
    }

    @GetMapping("/me")
    public Result<AuthDto.UserInfoVo> me() {
        return Result.ok(authService.getCurrentUserInfo(UserContextHolder.getUserId()));
    }
}
