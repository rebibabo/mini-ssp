package com.example.ssp.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class DspBidResponse {

    // OpenRTB 2.5: bid.price —— DSP 出价（CPM），为 null 或 0 表示不参与本次竞价。
    // Java 内部仍叫 bidPrice（更直观），对外 JSON 用 "price" 对齐协议。
    @JsonProperty("price")
    private BigDecimal bidPrice;

    public static DspBidResponse noBid() {
        DspBidResponse r = new DspBidResponse();
        r.setBidPrice(BigDecimal.ZERO);
        return r;
    }

    public boolean isValid() {
        return bidPrice != null && bidPrice.compareTo(BigDecimal.ZERO) > 0;
    }

    // 广告标题
    private String title;

    // 广告描述
    private String description;

    // 广告图片地址
    private String imageUrl;

    // 落地页地址
    private String clickUrl;
}
