package com.example.ssp.model.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class BidResponse {

    // 请求唯一标识，与请求中的 requestId 对应，由app生成
    private String requestId;

    // 广告位 ID
    private String adSlotId;

    // 中标 DSP 名称
    private String winDsp;

    // 中标价格（CPM）
    private BigDecimal winPrice;

    // 广告内容，包含素材和追踪链接
    private AdContentDTO adContent;
}
