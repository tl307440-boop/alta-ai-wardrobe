package com.wardrobe.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("inspo_like")
public class InspoLike {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String lookId;
    private LocalDateTime createdAt;
}
