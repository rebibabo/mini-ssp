package com.example.mockdsp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * SSP 发给 DSP 的竞价请求。
 * 字段需与 mini-ssp 的 DspBidRequest 对应（JSON 反序列化按 OpenRTB key 匹配）。
 * OpenRTB 2.5: id / tagid / bidfloor，须与 SSP 侧 @JsonProperty 保持一致。
 * device/user 这里用 Map 宽松接收，Mock DSP 不关心其具体结构。
 */
@Data
public class DspBidRequest {

    @JsonProperty("id")
    private String requestId;

    @JsonProperty("tagid")
    private String slotId;

    @JsonProperty("bidfloor")
    private BigDecimal floorPrice;
    private Map<String, Object> device;
    private Map<String, Object> user;
}
