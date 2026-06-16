package com.example.ssp.service.mock;

import com.example.ssp.model.dto.DspBidRequest;
import com.example.ssp.model.dto.DspBidResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Random;

/**
 * 进程内 Mock DSP，模拟真实 DSP 的竞价行为。
 * 不发真实 HTTP 请求，直接在内存中返回结果，用于开发和测试阶段。
 *
 * 两个压测专用配置（不影响正常开发，默认行为不变）：
 *   ssp.dsp.mock-random-seed  : 固定随机种子，让每次跑的出价/no_bid 序列完全一样，
 *                               消除业务随机性对压测数据的干扰。默认 -1（不固定，每次真随机）。
 *   ssp.dsp.mock-latency-ms   : 覆盖所有 DSP 的模拟延迟，设为 0 可消除 DSP 等待时间，
 *                               让压测结果只体现基础设施差异（cache/DB/Kafka）。默认 -1（用各 DSP 原始配置）。
 */
@Slf4j
@Component
public class MockDspHandler {

    // 固定种子：-1 = 不固定（每次真随机）；其他值 = 固定种子，压测时用
    @Value("${ssp.dsp.mock-random-seed:-1}")
    private long randomSeed;

    // 延迟覆盖：-1 = 用各 DSP 原始配置；0 = 无延迟（纯测基础设施）；其他值 = 统一延迟 ms
    @Value("${ssp.dsp.mock-latency-ms:-1}")
    private int mockLatencyMs;

    // Random 在 @PostConstruct 里初始化，因为 @Value 注入在构造函数之后才完成
    private Random random;

    @PostConstruct
    void init() {
        random = randomSeed < 0 ? new Random() : new Random(randomSeed);
        log.info("[MockDSP] random seed={}, latency override={}ms",
                randomSeed < 0 ? "random" : randomSeed,
                mockLatencyMs < 0 ? "per-dsp" : mockLatencyMs);
    }

    // 每个 DSP 的模拟出价范围（最低价, 最高价）
    private static final Map<String, int[]> DSP_BID_RANGES = Map.of(
            "dsp-001", new int[]{100, 500},   // 出价 1.00 ~ 5.00 元
            "dsp-002", new int[]{200, 800},   // 出价 2.00 ~ 8.00 元
            "dsp-003", new int[]{50, 300}     // 出价 0.50 ~ 3.00 元
    );

    // 每个 DSP 的原始模拟响应延迟（毫秒），可被 mock-latency-ms 覆盖
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
        // mockLatencyMs >= 0 时覆盖所有 DSP 的延迟（0 = 无延迟，压测基础设施用）
        int latency = mockLatencyMs >= 0 ? mockLatencyMs : DSP_LATENCY.getOrDefault(dspId, 100);
        if (latency == 0) return;
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
