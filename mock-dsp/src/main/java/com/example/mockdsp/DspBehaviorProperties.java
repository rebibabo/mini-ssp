package com.example.mockdsp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mock DSP 行为配置，从 application.yml 的 {@code mock.dsp.*} 读取。
 * 每个 profile(dsp-a/dsp-b/dsp-c) 配一套不同的值，模拟出不同性格的 DSP。
 */
@Data
@ConfigurationProperties(prefix = "mock.dsp")
public class DspBehaviorProperties {

    /** DSP 名字（出现在广告内容里，便于辨认是哪个 DSP 中标） */
    private String name = "mock-dsp";

    /** 出价下限（单位：分），如 100 = 1.00 元 */
    private int minBidCents = 100;

    /** 出价上限（单位：分） */
    private int maxBidCents = 500;

    /** 模拟响应延迟下限（毫秒） */
    private int latencyMinMs = 30;

    /** 模拟响应延迟上限（毫秒） */
    private int latencyMaxMs = 100;

    /** 不出价概率 0.0~1.0 */
    private double noBidRate = 0.1;

    /** 返回 HTTP 500 异常的概率 0.0~1.0（模拟 DSP 故障） */
    private double errorRate = 0.0;

    /** 触发超时的概率 0.0~1.0（命中时额外 sleep 一个超长延迟，让 SSP 端超时） */
    private double timeoutRate = 0.0;

    /** 超时模拟时额外睡眠的时长（毫秒），需大于 SSP 端的 DSP 超时阈值 */
    private int timeoutSleepMs = 500;
}
