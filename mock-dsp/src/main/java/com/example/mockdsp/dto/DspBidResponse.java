package com.example.mockdsp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * DSP 返回给 SSP 的竞价响应。
 * 字段需与 mini-ssp 的 DspBidResponse 对应。bidPrice 为 0 表示不出价。
 * OpenRTB 2.5: bid.price —— 对外 JSON 用 "price"，须与 SSP 侧 @JsonProperty 保持一致。
 */
@Data
public class DspBidResponse {

    @JsonProperty("price")
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
