package com.example.ssp.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "广告位配置入参")
@Data
public class AdSlotDTO {

    @Schema(description = "广告位唯一标识", example = "slot-test-001", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String slotId;

    @Schema(description = "广告位名称", example = "首页横幅", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String name;

    @Schema(description = "宽度（px）", example = "640", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Integer width;

    @Schema(description = "高度（px）", example = "100", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Integer height;

    @Schema(description = "类型：1=横幅 2=插屏 3=开屏 4=信息流", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Integer type;

    @Schema(description = "底价（CPM）", example = "0.50", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private BigDecimal floorPrice;

    @Schema(description = "状态：0=禁用 1=启用", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Integer status;
}
