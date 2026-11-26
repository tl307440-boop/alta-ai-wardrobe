package com.wardrobe.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("try_on_job")
public class TryOnJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long planId;
    private String planThemeTitle;
    private String status; // QUEUED | PROCESSING | SUCCEEDED | FAILED
    private String stylePreset;
    private String generationMode;
    private Integer useReferencePhoto;
    private Integer usedRealImageModel;
    private Integer usedReferencePhoto;
    private String modelUsed;
    private String endpointUsed;
    private String promptUsed;
    private String visualReport;
    private String previewImageUrl;
    private String notice;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
