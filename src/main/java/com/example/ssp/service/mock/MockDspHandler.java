package com.example.ssp.service.mock;

import com.example.ssp.model.dto.DspBidRequest;
import com.example.ssp.model.dto.DspBidResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Random;

/**
 * 进程内 Mock DSP，模拟真实 DSP 的竞价行为。
 * 不发真实 HTTP 请求，直接在内存中返回结果，用于开发和测试阶段。
 */
@Slf4j
@Component
public class MockDspHandler {

    private final Random random = new Random();

    // 每个 DSP 的模拟出价范围（最低价, 最高价）
    private static final Map<String, int[]> DSP_BID_RANGES = Map.of(
            "dsp-001", new int[]{100, 500},   // 出价 1.00 ~ 5.00 元
            "dsp-002", new int[]{200, 800},   // 出价 2.00 ~ 8.00 元
            "dsp-003", new int[]{50, 300}     // 出价 0.50 ~ 3.00 元
    );

    // 每个 DSP 的模拟响应延迟（毫秒）
    private static final Map<String, Integer> DSP_LATENCY = Map.of(
            "dsp-001", 50,
            "dsp-002", 80,
            "dsp-003", 120
    );

    // 每个 DSP 的模拟无响应概率（0.0 ~ 1.0）
    private static final Map<String, Double> DSP_NO_BID_RATE = Map.of(
            "dsp-001", 0.1,   // 10% 概率不出价
            "dsp-002", 0.2,
            "dsp-003", 0.3
    );

    /**
     * 模拟向某个 DSP 发送竞价请求并获取响应。
     */
    public DspBidResponse bid(String dspId, DspBidRequest request) {
        simulateLatency(dspId);

        // 模拟一定概率不出价
        double noBidRate = DSP_NO_BID_RATE.getOrDefault(dspId, 0.2);
        if (random.nextDouble() < noBidRate) {
            log.debug("[MockDSP] {} no bid for slot {}", dspId, request.getSlotId());
            return DspBidResponse.noBid();
        }

        // 生成随机出价（单位：分，转成元）
        int[] range = DSP_BID_RANGES.getOrDefault(dspId, new int[]{100, 500});
        int bidCents = range[0] + random.nextInt(range[1] - range[0]);
        BigDecimal bidPrice = BigDecimal.valueOf(bidCents).divide(BigDecimal.valueOf(100));

        // 低于底价则不出价
        if (bidPrice.compareTo(request.getFloorPrice()) < 0) {
            log.debug("[MockDSP] {} bid {} below floor {}", dspId, bidPrice, request.getFloorPrice());
            return DspBidResponse.noBid();
        }

        DspBidResponse response = new DspBidResponse();
        response.setBidPrice(bidPrice);
        response.setTitle("来自 " + dspId + " 的广告");
        response.setDescription("优质广告内容");
        response.setImageUrl("https://mock-cdn.example.com/" + dspId + "/banner.jpg");
        response.setClickUrl("https://mock-landing.example.com/" + dspId);

        log.debug("[MockDSP] {} bid {} for slot {}", dspId, bidPrice, request.getSlotId());
        return response;
    }

    private void simulateLatency(String dspId) {
        int latency = DSP_LATENCY.getOrDefault(dspId, 100);
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
