package com.example.ssp.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "DSP 配置入参")
@Data
public class DspConfigDTO {

    @Schema(description = "DSP 唯一标识", example = "dsp-001", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String dspId;

    @Schema(description = "DSP 名称", example = "DSP一号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String name;

    @Schema(description = "竞价请求地址（HTTP 模式下 SSP 向此地址 POST 竞价）", example = "http://localhost:8081/bid", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String bidUrl;

    @Schema(description = "单 DSP 超时时间（毫秒）", example = "150", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Integer timeoutMs;

    @Schema(description = "QPS 上限，0 表示不限", example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Integer qpsLimit;

    @Schema(description = "状态：0=禁用 1=启用", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Integer status;
}
