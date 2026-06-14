package com.example.ssp.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dsp_config")
public class DspConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String dspId;

    private String name;

    private String bidUrl;

    private Integer timeoutMs;

    // 0=不限
    private Integer qpsLimit;

    // 0=禁用 1=启用
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
