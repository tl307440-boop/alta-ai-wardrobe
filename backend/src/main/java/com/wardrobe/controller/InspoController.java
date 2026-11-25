package com.wardrobe.controller;

import com.wardrobe.common.Result;
import com.wardrobe.model.dto.InspoDto;
import com.wardrobe.security.UserContextHolder;
import com.wardrobe.service.InspoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inspo")
@RequiredArgsConstructor
public class InspoController {

    private final InspoService inspoService;

    @GetMapping("/feed")
    public Result<InspoDto.FeedResponse> feed(
            @RequestParam(defaultValue = "for_you") String tab,
            @RequestParam(defaultValue = "all") String gender,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean likedOnly) {
        return Result.ok(inspoService.getFeed(UserContextHolder.getUserId(), tab, gender, q, likedOnly));
    }

    @PostMapping("/looks/{lookId}/like")
    public Result<InspoDto.LikeToggleVo> toggleLike(@PathVariable String lookId) {
        return Result.ok(inspoService.toggleLike(UserContextHolder.getUserId(), lookId));
    }
}
