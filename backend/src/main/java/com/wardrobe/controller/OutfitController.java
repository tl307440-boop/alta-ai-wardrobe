package com.wardrobe.controller;

import com.wardrobe.common.Result;
import com.wardrobe.model.dto.OutfitDto;
import com.wardrobe.security.UserContextHolder;
import com.wardrobe.service.CommerceMatchService;
import com.wardrobe.service.OutfitRecommendationService;
import com.wardrobe.service.TryOnJobService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OutfitController {
    private final OutfitRecommendationService outfitService;
    private final TryOnJobService tryOnJobService;
    private final CommerceMatchService commerceMatchService;

    @PostMapping("/outfit/recommend")
    public Result<OutfitDto.PlanVo> recommend(@RequestBody(required = false) OutfitDto.RecommendRequest req) {
        OutfitDto.RecommendRequest body = req != null ? req : new OutfitDto.RecommendRequest();
        return Result.ok(outfitService.recommend(UserContextHolder.getUserId(), body));
    }

    @PostMapping("/outfit/critique")
    public Result<OutfitDto.OutfitCritiqueVo> critiqueOutfit(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String sceneTag,
            @RequestParam(required = false) String weatherInfo,
            @RequestParam(required = false) String itemIds,
            @RequestParam(defaultValue = "studio_canvas") String source) {
        return Result.ok(outfitService.critiqueOutfit(
                UserContextHolder.getUserId(), file, sceneTag, weatherInfo, itemIds, source));
    }

    @PostMapping("/outfit/plans/{id}/swap-slot")
    public Result<OutfitDto.PlanVo> swapSlot(@PathVariable Long id, @RequestBody OutfitDto.SwapSlotRequest req) {
        return Result.ok(outfitService.swapSlot(UserContextHolder.getUserId(), id, req));
    }

    @PutMapping("/outfit/plans/{id}/collage-layout")
    public Result<OutfitDto.PlanVo> saveCollageLayout(@PathVariable Long id,
                                                      @RequestBody OutfitDto.SaveCollageLayoutRequest req) {
        return Result.ok("自由拼贴画板排版已保存",
                outfitService.saveCollageLayout(UserContextHolder.getUserId(), id, req));
    }

    @PostMapping("/outfit/plans/{id}/items")
    public Result<OutfitDto.PlanVo> addPlanItem(@PathVariable Long id,
                                                @jakarta.validation.Valid @RequestBody OutfitDto.AddPlanItemRequest req) {
        return Result.ok("已添加到当前穿搭",
                outfitService.addPlanItem(UserContextHolder.getUserId(), id, req.getItemId()));
    }

    @DeleteMapping("/outfit/plans/{id}/items/{itemId}")
    public Result<OutfitDto.PlanVo> removePlanItem(@PathVariable Long id, @PathVariable Long itemId) {
        return Result.ok("已从当前穿搭移除",
                outfitService.removePlanItem(UserContextHolder.getUserId(), id, itemId));
    }

    @PostMapping("/outfit/plans/{id}/try-on")
    public Result<OutfitDto.TryOnResultVo> generateTryOn(@PathVariable Long id,
                                                         @RequestBody(required = false) OutfitDto.TryOnRequest req,
                                                         HttpServletRequest httpReq) {
        OutfitDto.TryOnRequest body = req != null ? req : new OutfitDto.TryOnRequest();
        enrichClientOrigin(body, httpReq);
        return Result.ok(outfitService.generateTryOnPreview(UserContextHolder.getUserId(), id, body));
    }

    @PostMapping("/outfit/plans/{id}/try-on/jobs")
    public Result<OutfitDto.TryOnJobVo> submitTryOnJob(@PathVariable Long id,
                                                       @RequestBody(required = false) OutfitDto.TryOnRequest req,
                                                       HttpServletRequest httpReq) {
        OutfitDto.TryOnRequest body = req != null ? req : new OutfitDto.TryOnRequest();
        enrichClientOrigin(body, httpReq);
        return Result.ok("已提交后台生图任务",
                tryOnJobService.submitJob(UserContextHolder.getUserId(), id, body));
    }

    private void enrichClientOrigin(OutfitDto.TryOnRequest body, HttpServletRequest httpReq) {
        if (body == null || (body.getClientOrigin() != null && !body.getClientOrigin().isBlank()) || httpReq == null) {
            return;
        }
        String proto = httpReq.getHeader("X-Forwarded-Proto");
        if (proto == null || proto.isBlank()) {
            proto = httpReq.getScheme();
        }
        String hostHeader = httpReq.getHeader("X-Forwarded-Host");
        if (hostHeader == null || hostHeader.isBlank()) {
            hostHeader = httpReq.getHeader("Host");
        }
        if (hostHeader != null && !hostHeader.isBlank()) {
            String firstHost = hostHeader.split(",")[0].trim();
            body.setClientOrigin(proto + "://" + firstHost);
            return;
        }
        String serverName = httpReq.getServerName();
        int port = httpReq.getServerPort();
        boolean defaultPort = ("http".equalsIgnoreCase(proto) && port == 80) || ("https".equalsIgnoreCase(proto) && port == 443);
        body.setClientOrigin(proto + "://" + serverName + (defaultPort ? "" : ":" + port));
    }

    @GetMapping("/outfit/try-on/jobs")
    public Result<List<OutfitDto.TryOnJobVo>> listTryOnJobs(
            @RequestParam(required = false) Long planId,
            @RequestParam(defaultValue = "18") int limit) {
        return Result.ok(tryOnJobService.listJobs(UserContextHolder.getUserId(), planId, limit));
    }

    @GetMapping("/outfit/try-on/jobs/{jobId}")
    public Result<OutfitDto.TryOnJobVo> getTryOnJob(@PathVariable Long jobId) {
        return Result.ok(tryOnJobService.getJob(UserContextHolder.getUserId(), jobId));
    }

    @PostMapping("/outfit/try-on/jobs/{jobId}/apply-cover")
    public Result<OutfitDto.PlanVo> applyTryOnJobCover(@PathVariable Long jobId,
                                                       @RequestParam(required = false) Long planId) {
        return Result.ok("已设为当前穿搭方案预览封面",
                tryOnJobService.applyJobAsCover(UserContextHolder.getUserId(), jobId, planId));
    }

    @DeleteMapping("/outfit/try-on/jobs/{jobId}")
    public Result<Void> deleteTryOnJob(@PathVariable Long jobId) {
        tryOnJobService.deleteJob(UserContextHolder.getUserId(), jobId);
        return Result.ok();
    }

    @GetMapping("/outfit/plans/{id}")
    public Result<OutfitDto.PlanVo> getPlan(@PathVariable Long id) {
        return Result.ok(outfitService.getPlanById(UserContextHolder.getUserId(), id));
    }

    @GetMapping("/outfit/plans")
    public Result<List<OutfitDto.PlanVo>> listPlans(@RequestParam(defaultValue = "false") boolean favoriteOnly) {
        return Result.ok(outfitService.listPlans(UserContextHolder.getUserId(), favoriteOnly));
    }

    @PatchMapping("/outfit/plans/{id}/favorite")
    public Result<OutfitDto.PlanVo> toggleFavorite(@PathVariable Long id) {
        return Result.ok(outfitService.toggleFavorite(UserContextHolder.getUserId(), id));
    }

    @PostMapping("/outfit/plans/{id}/dislike")
    public Result<OutfitDto.PlanVo> dislikePlan(@PathVariable Long id) {
        return Result.ok("已记录避雷偏好并为你重新生成替代方案",
                outfitService.dislikePlan(UserContextHolder.getUserId(), id));
    }

    @DeleteMapping("/outfit/plans/{id}")
    public Result<Void> deletePlan(@PathVariable Long id) {
        outfitService.deletePlan(UserContextHolder.getUserId(), id);
        return Result.ok();
    }

    @GetMapping("/outfit/history")
    public Result<List<OutfitDto.HistoryVo>> listHistory(@RequestParam(required = false) String month) {
        return Result.ok(outfitService.listHistory(UserContextHolder.getUserId(), month));
    }

    @PostMapping("/outfit/history")
    public Result<OutfitDto.HistoryVo> recordHistory(@RequestBody OutfitDto.RecordHistoryRequest req) {
        return Result.ok(outfitService.recordHistory(UserContextHolder.getUserId(), req));
    }

    @PutMapping("/outfit/history/{id}")
    public Result<OutfitDto.HistoryVo> updateHistory(@PathVariable Long id, @RequestBody OutfitDto.UpdateHistoryRequest req) {
        return Result.ok(outfitService.updateHistory(UserContextHolder.getUserId(), id, req));
    }

    @PostMapping("/outfit/history/{id}/photo")
    public Result<OutfitDto.HistoryVo> uploadHistoryPhoto(@PathVariable Long id,
                                                          @RequestParam("file") MultipartFile file) {
        return Result.ok("当日实拍 OOTD 照片已上传",
                outfitService.uploadHistoryPhoto(UserContextHolder.getUserId(), id, file));
    }

    @DeleteMapping("/outfit/history/{id}/photo")
    public Result<OutfitDto.HistoryVo> deleteHistoryPhoto(@PathVariable Long id) {
        return Result.ok("已移除当日实拍照片",
                outfitService.deleteHistoryPhoto(UserContextHolder.getUserId(), id));
    }

    @PostMapping("/outfit/history/ootd-checkin")
    public Result<OutfitDto.HistoryVo> checkinOotd(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate wearDate,
            @RequestParam(required = false) String themeTitle,
            @RequestParam(required = false) String sceneTag,
            @RequestParam(required = false) String weatherInfo,
            @RequestParam(required = false) Integer weatherTemp,
            @RequestParam(required = false) String weatherIcon,
            @RequestParam(required = false) String itemIds,
            @RequestParam(required = false) String notes,
            @RequestParam(required = false) Integer feedbackScore) {
        return Result.ok("实拍 OOTD 打卡已写入穿搭日历",
                outfitService.checkinOotd(UserContextHolder.getUserId(), file, wearDate, themeTitle,
                        sceneTag, weatherInfo, weatherTemp, weatherIcon, itemIds, notes, feedbackScore));
    }

    @GetMapping("/products")
    public Result<List<OutfitDto.ProductVo>> listProducts(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) BigDecimal maxPrice) {
        return Result.ok(commerceMatchService.listAll(category, maxPrice));
    }
}
