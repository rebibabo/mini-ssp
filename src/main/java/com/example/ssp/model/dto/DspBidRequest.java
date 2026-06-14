package com.example.ssp.model.dto;

import lombok.Data;

import java.math.BigDecimal;

// 媒体(App)  --BidRequest-->  SSP  --DspBidRequest-->  DSP
@Data
public class DspBidRequest {

    // 请求唯一标识，用于追踪
    private String requestId;

    // 广告位 ID
    private String slotId;

    // 底价（CPM），DSP 出价低于此价无效
    private BigDecimal floorPrice;

    // 设备信息，DSP 用于判断投放策略
    private DeviceDTO device;

    // 用户信息，DSP 用于精准投放
    private UserDTO user;
}
