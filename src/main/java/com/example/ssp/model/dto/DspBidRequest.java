package com.example.ssp.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

// 媒体(App)  --BidRequest-->  SSP  --DspBidRequest-->  DSP
// 这条线是真正的 OpenRTB 接口：DspBidRequest 对应 OpenRTB BidRequest。
// Java 字段名保留可读命名，对外 JSON 用 @JsonProperty 对齐 OpenRTB；SSP/mock-dsp 两边须一致。
@Data
public class DspBidRequest {

    // OpenRTB 2.5: BidRequest.id —— 请求唯一标识，用于追踪
    @JsonProperty("id")
    private String requestId;

    // OpenRTB 2.5: imp[].tagid —— 广告位标签 ID
    @JsonProperty("tagid")
    private String slotId;

    // OpenRTB 2.5: imp[].bidfloor —— 底价（CPM），DSP 出价低于此价无效
    @JsonProperty("bidfloor")
    private BigDecimal floorPrice;

    // 设备信息，DSP 用于判断投放策略
    private DeviceDTO device;

    // 用户信息，DSP 用于精准投放
    private UserDTO user;
}
