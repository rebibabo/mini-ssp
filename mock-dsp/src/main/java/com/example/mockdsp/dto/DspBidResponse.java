package com.example.mockdsp.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * DSP 返回给 SSP 的竞价响应。
 * 字段需与 mini-ssp 的 DspBidResponse 对应。bidPrice 为 0 表示不出价。
 */
@Data
public class DspBidResponse {

    private BigDecimal bidPrice;
    private String title;
    private String description;
    private String imageUrl;
    private String clickUrl;

    public static DspBidResponse noBid() {
        DspBidResponse r = new DspBidResponse();
        r.setBidPrice(BigDecimal.ZERO);
        return r;
    }
}
