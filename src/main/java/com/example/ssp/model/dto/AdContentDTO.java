package com.example.ssp.model.dto;

import lombok.Data;

@Data
public class AdContentDTO {

    // 广告标题，如"新品首发"
    private String title;

    // 广告描述文字，如"限时优惠，立即抢购"
    private String description;

    // 广告图片地址，媒体展示此图
    private String imageUrl;

    // 落地页地址，用户点击广告后最终跳转到这里
    private String clickUrl;

    // 曝光上报地址，广告展示后媒体调用此 URL 通知 SSP 记录曝光
    private String impressionTrackUrl;

    // 点击追踪地址，用户点击后媒体先调用此 URL，SSP 记录点击再跳转落地页
    private String clickTrackUrl;
}
