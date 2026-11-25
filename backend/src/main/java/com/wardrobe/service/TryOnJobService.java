package com.wardrobe.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wardrobe.common.BusinessException;
import com.wardrobe.common.ErrorCode;
import com.wardrobe.mapper.TryOnJobMapper;
import com.wardrobe.model.dto.OutfitDto;
import com.wardrobe.model.entity.OutfitPlan;
import com.wardrobe.model.entity.TryOnJob;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TryOnJobService {
    private static final int STALE_JOB_MINUTES = 4;

    private final TryOnJobMapper tryOnJobMapper;
    private final OutfitRecommendationService outfitRecommendationService;

    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "tryon-job-worker");
        t.setDaemon(true);
        return t;
    });

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedJobsOnStartup() {
        List<TryOnJob> interrupted = tryOnJobMapper.selectList(
                new LambdaQueryWrapper<TryOnJob>().in(TryOnJob::getStatus, List.of("QUEUED", "PROCESSING")));
        if (interrupted.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (TryOnJob job : interrupted) {
            job.setStatus("FAILED");
            job.setErrorMessage("服务重启导致本次生成中断，请重新发起生成");
            job.setNotice("服务重启导致本次生成中断，请重新发起生成");
            job.setUpdatedAt(now);
            tryOnJobMapper.updateById(job);
        }
        log.info("Recovered {} interrupted try-on job(s) on startup.", interrupted.size());
    }

    @PreDestroy
    public void shutdownExecutor() {
        executor.shutdownNow();
    }

    /**
     * Submits an asynchronous try-on generation job.
     * - Immediately persists a DB record (status=PROCESSING) and returns so page refresh never loses state.
     * - Reuses an already-running job for the same (userId, planId) to prevent duplicate paid API calls.
     */
    public OutfitDto.TryOnJobVo submitJob(Long userId, Long planId, OutfitDto.TryOnRequest req) {
        OutfitPlan plan = outfitRecommendationService.requireOwnedPlan(userId, planId);
        sweepStaleJobsForUser(userId);

        // Idempotency guard: if this plan already has an active job, return it instead of double-billing
        TryOnJob existingActive = tryOnJobMapper.selectOne(
                new LambdaQueryWrapper<TryOnJob>()
                        .eq(TryOnJob::getUserId, userId)
                        .eq(TryOnJob::getPlanId, planId)
                        .in(TryOnJob::getStatus, List.of("QUEUED", "PROCESSING"))
                        .orderByDesc(TryOnJob::getId)
                        .last("LIMIT 1"));
        if (existingActive != null) {
            return toVo(existingActive);
        }

        OutfitDto.TryOnRequest safeReq = req != null ? req : new OutfitDto.TryOnRequest();
        LocalDateTime now = LocalDateTime.now();
        TryOnJob job = new TryOnJob();
        job.setUserId(userId);
        job.setPlanId(planId);
        job.setPlanThemeTitle(plan.getThemeTitle());
        job.setStatus("PROCESSING");
        job.setStylePreset(safeReq.getStylePreset() != null && !safeReq.getStylePreset().isBlank()
                ? safeReq.getStylePreset().trim() : "studio_white");
        job.setGenerationMode(safeReq.getGenerationMode() != null && !safeReq.getGenerationMode().isBlank()
                ? safeReq.getGenerationMode().trim() : "auto");
        job.setUseReferencePhoto(Boolean.FALSE.equals(safeReq.getUseReferencePhoto()) ? 0 : 1);
        job.setUsedRealImageModel(0);
        job.setUsedReferencePhoto(0);
        job.setNotice("任务已提交至后台队列，刷新或切换页面都不会中断生成");
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        tryOnJobMapper.insert(job);

        Long jobId = job.getId();
        executor.submit(() -> executeJobAsync(jobId, userId, planId, safeReq));
        return toVo(job);
    }

    private void executeJobAsync(Long jobId, Long userId, Long planId, OutfitDto.TryOnRequest req) {
        try {
            OutfitDto.TryOnResultVo res = outfitRecommendationService.generateTryOnPreview(userId, planId, req);
            TryOnJob job = tryOnJobMapper.selectById(jobId);
            if (job == null) {
                return;
            }
            job.setStatus("SUCCEEDED");
            job.setPreviewImageUrl(res.getPreviewImageUrl());
            job.setUsedRealImageModel(res.isUsedRealImageModel() ? 1 : 0);
            job.setUsedReferencePhoto(res.isUsedReferencePhoto() ? 1 : 0);
            job.setModelUsed(res.getModelUsed());
            job.setEndpointUsed(res.getEndpointUsed());
            job.setPromptUsed(res.getPromptUsed());
            job.setNotice(res.getNotice());
            job.setErrorMessage(null);
            job.setUpdatedAt(LocalDateTime.now());
            tryOnJobMapper.updateById(job);
        } catch (Exception e) {
            log.warn("Async try-on job {} failed: {}", jobId, e.getMessage());
            TryOnJob job = tryOnJobMapper.selectById(jobId);
            if (job != null) {
                job.setStatus("FAILED");
                String msg = e.getMessage() != null ? e.getMessage() : "生图任务执行失败";
                job.setErrorMessage(msg.length() > 480 ? msg.substring(0, 480) : msg);
                job.setNotice("生图任务失败：" + job.getErrorMessage());
                job.setUpdatedAt(LocalDateTime.now());
                tryOnJobMapper.updateById(job);
            }
        }
    }

    public OutfitDto.TryOnJobVo getJob(Long userId, Long jobId) {
        TryOnJob job = requireOwnedJob(userId, jobId);
        sweepIfStale(job);
        return toVo(job);
    }

    public List<OutfitDto.TryOnJobVo> listJobs(Long userId, Long planId, int limit) {
        sweepStaleJobsForUser(userId);
        int safeLimit = Math.max(1, Math.min(limit, 50));
        LambdaQueryWrapper<TryOnJob> qw = new LambdaQueryWrapper<TryOnJob>()
                .eq(TryOnJob::getUserId, userId)
                .orderByDesc(TryOnJob::getId)
                .last("LIMIT " + safeLimit);
        if (planId != null) {
            qw.eq(TryOnJob::getPlanId, planId);
        }
        return tryOnJobMapper.selectList(qw).stream()
                .map(this::toVo)
                .collect(Collectors.toList());
    }

    public OutfitDto.PlanVo applyJobAsCover(Long userId, Long jobId, Long targetPlanId) {
        TryOnJob job = requireOwnedJob(userId, jobId);
        if (job.getPreviewImageUrl() == null || job.getPreviewImageUrl().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "该生成任务尚未产出可用图片");
        }
        Long planId = targetPlanId != null ? targetPlanId : job.getPlanId();
        return outfitRecommendationService.applyTryOnCover(userId, planId, job.getPreviewImageUrl());
    }

    public void deleteJob(Long userId, Long jobId) {
        TryOnJob job = requireOwnedJob(userId, jobId);
        tryOnJobMapper.deleteById(job.getId());
    }

    private TryOnJob requireOwnedJob(Long userId, Long jobId) {
        TryOnJob job = tryOnJobMapper.selectById(jobId);
        if (job == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该生图任务记录");
        }
        if (!Objects.equals(job.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return job;
    }

    private void sweepStaleJobsForUser(Long userId) {
        List<TryOnJob> active = tryOnJobMapper.selectList(
                new LambdaQueryWrapper<TryOnJob>()
                        .eq(TryOnJob::getUserId, userId)
                        .in(TryOnJob::getStatus, List.of("QUEUED", "PROCESSING")));
        for (TryOnJob job : active) {
            sweepIfStale(job);
        }
    }

    private void sweepIfStale(TryOnJob job) {
        if (!"QUEUED".equals(job.getStatus()) && !"PROCESSING".equals(job.getStatus())) {
            return;
        }
        LocalDateTime refTime = job.getUpdatedAt() != null ? job.getUpdatedAt() : job.getCreatedAt();
        if (refTime != null && refTime.isBefore(LocalDateTime.now().minusMinutes(STALE_JOB_MINUTES))) {
            job.setStatus("FAILED");
            job.setErrorMessage("任务执行超时（超过 " + STALE_JOB_MINUTES + " 分钟未响应），已自动回收状态");
            job.setNotice(job.getErrorMessage());
            job.setUpdatedAt(LocalDateTime.now());
            tryOnJobMapper.updateById(job);
        }
    }

    private OutfitDto.TryOnJobVo toVo(TryOnJob job) {
        return OutfitDto.TryOnJobVo.builder()
                .id(job.getId())
                .planId(job.getPlanId())
                .planThemeTitle(job.getPlanThemeTitle())
                .status(job.getStatus())
                .stylePreset(job.getStylePreset())
                .generationMode(job.getGenerationMode())
                .useReferencePhoto(job.getUseReferencePhoto() != null && job.getUseReferencePhoto() == 1)
                .usedRealImageModel(job.getUsedRealImageModel() != null && job.getUsedRealImageModel() == 1)
                .usedReferencePhoto(job.getUsedReferencePhoto() != null && job.getUsedReferencePhoto() == 1)
                .modelUsed(job.getModelUsed())
                .endpointUsed(job.getEndpointUsed())
                .promptUsed(job.getPromptUsed())
                .previewImageUrl(job.getPreviewImageUrl())
                .notice(job.getNotice())
                .errorMessage(job.getErrorMessage())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }
}
