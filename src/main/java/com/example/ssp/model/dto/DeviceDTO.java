package com.example.ssp.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "设备信息，DSP 据此判断投放策略")
@Data
public class DeviceDTO {

    @Schema(description = "操作系统", example = "iOS")
    private String os;

    // OpenRTB 2.5: device.osv —— 操作系统版本
    @JsonProperty("osv")
    @Schema(description = "操作系统版本（OpenRTB osv）", example = "14")
    private String osVersion;

    @Schema(description = "设备型号", example = "OPPO Find X7")
    private String model;

    @Schema(description = "用户 IP 地址", example = "1.2.3.4")
    private String ip;

    @Schema(description = "User-Agent，标识浏览器/App 身份")
    private String ua;
}
