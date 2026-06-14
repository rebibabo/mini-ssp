package com.example.mockdsp.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * SSP 发给 DSP 的竞价请求。
 * 字段需与 mini-ssp 的 DspBidRequest 对应（JSON 反序列化按字段名匹配）。
 * device/user 这里用 Map 宽松接收，Mock DSP 不关心其具体结构。
 */
@Data
public class DspBidRequest {

    private String requestId;
    private String slotId;
    private BigDecimal floorPrice;
    private Map<String, Object> device;
    private Map<String, Object> user;
}
