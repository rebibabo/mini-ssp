package com.example.ssp.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

// 媒体(App)  --BidRequest-->  SSP  --DspBidRequest-->  DSP
@Schema(description = "竞价请求：媒体/App 发起广告请求时的入参")
@Data
public class BidRequest {

    @Schema(description = "请求唯一标识，由媒体生成，贯穿全链路追踪", example = "req-20260614-1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String requestId;

    @Schema(description = "广告位 ID，标识本次请求来自哪个广告位", example = "slot-test-001", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String adSlotId;

    @Schema(description = "设备信息，必填", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Valid
    private DeviceDTO device;

    @Schema(description = "用户信息，可为空（匿名用户）")
    private UserDTO user;
}
