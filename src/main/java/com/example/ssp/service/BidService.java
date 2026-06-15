package com.example.ssp.service;

import com.example.ssp.cache.FrequencyCapService;
import com.example.ssp.cache.RateLimiter;
import com.example.ssp.cache.SlotCacheService;
import com.example.ssp.exception.BizException;
import com.example.ssp.model.dto.*;
import com.example.ssp.model.entity.AdSlot;
import com.example.ssp.model.entity.BidLog;
import com.example.ssp.model.entity.DspConfig;
import com.example.ssp.service.bidlog.BidLogWriter;
import com.example.ssp.service.dsp.DspCaller;
import com.example.ssp.service.pricing.PricingStrategy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class BidService {

    private final SlotCacheService slotCacheService;
    private final TrackService trackService;
    // bid_log 落库策略：按 ssp.bid-log.mode 注入 DirectBidLogWriter(直写) 或 KafkaBidLogWriter(发Kafka)
    private final BidLogWriter bidLogWriter;
    // 策略接口：按 ssp.dsp.mode 配置注入 MockDspCaller(进程内) 或 DspBidClient(HTTP)
    private final DspCaller dspCaller;
    private final RateLimiter rateLimiter;
    // 频次控制：按 (用户,DSP) 维度限制每日展示次数
    private final FrequencyCapService frequencyCapService;
    // 计价策略：按 ssp.bid.auction-type 配置注入 FirstPricePricing(一价) 或 SecondPricePricing(二价)
    private final PricingStrategy pricingStrategy;
    // 指标注册表：往这里打 Counter/Timer，会被 /actuator/prometheus 暴露出去
    private final MeterRegistry meterRegistry;

    // Executor 是接口（线程池是它的实现）。容器里可能有多个 Executor 类型的 Bean，
    // 光按类型 Spring 不知道注入哪个，用 @Qualifier 按名字点名要 "bidExecutor" 这个 Bean
    // （对应 ThreadPoolConfig 里 @Bean("bidExecutor")）。
    @Qualifier("bidExecutor")
    private final Executor bidExecutor;

    // @Value 从 application.yml 读取全局竞价超时（默认 200ms）；单元测试无容器，需用 ReflectionTestUtils 手动设值
    @Value("${ssp.bid.global-timeout-ms:200}")
    private long globalTimeoutMs;

    // 频次上限：同一用户对同一 DSP 广告每天最多展示次数(0=不限)
    @Value("${ssp.freq.daily-cap:3}")
    private int freqDailyCap;

    /**
     * 核心竞价入口
     */
    public BidResponse processBid(BidRequest request) {
        // 整次竞价的总耗时：不管从哪个 return 出去，finally 都会执行并记录
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // 1. 查广告位（走缓存）
            AdSlot adSlot = slotCacheService.getSlot(request.getAdSlotId());
            if (adSlot == null) {
                throw new BizException(404, "广告位不存在或已禁用: " + request.getAdSlotId());
            }

            // 2. 查关联的 DSP 列表（走缓存）
            List<DspConfig> dsps = slotCacheService.getDspsForSlot(request.getAdSlotId());
            if (dsps.isEmpty()) {
                log.warn("[Bid] no dsps for slot {}", request.getAdSlotId());
                meterRegistry.counter("ssp_bid_requests_total", "result", "no_fill").increment();
                return null;  // no fill
            }

            // 2.5 频次控制：剔除该用户今日已看够的 DSP（匿名用户无法追踪，跳过）
            String userId = request.getUser() != null ? request.getUser().getUserId() : null;
            if (userId != null && !userId.isBlank()) {
                String uid = userId;  // lambda 需要 effectively final
                dsps = dsps.stream()
                        .filter(d -> !frequencyCapService.isCapped(uid, d.getDspId(), freqDailyCap))
                        .toList();
                if (dsps.isEmpty()) {
                    log.info("[Bid] all dsps frequency-capped for user {}, slot {}", uid, request.getAdSlotId());
                    meterRegistry.counter("ssp_bid_requests_total", "result", "no_fill").increment();
                    return null;  // no fill
                }
            }

            // 3. 并发向每个 DSP 发起竞价
            // 每个 DSP 在 bidExecutor 线程池里独立执行，互不阻塞
            List<CompletableFuture<DspBidResult>> futures = new ArrayList<>();

            for (DspConfig dsp : dsps) {
                DspBidRequest dspRequest = buildDspRequest(request, adSlot, dsp);
                CompletableFuture<DspBidResult> future = CompletableFuture
                        // supplyAsync：把 callDsp 任务丢进线程池异步执行，立即返回 Future
                        .supplyAsync(() -> callDsp(dsp, dspRequest), bidExecutor)
                        // exceptionally：callDsp 抛异常时兜底，用 error 结果代替，保证 Future 不崩
                        .exceptionally(ex -> {
                            log.error("[Bid] dsp {} error: {}", dsp.getDspId(), ex.getMessage());
                            return DspBidResult.error(dsp.getDspId());
                        });
                futures.add(future);
            }

            // 4. 等待所有 DSP 响应（最长 globalTimeoutMs）
            // allOf 相当于闹钟：等所有 Future 完成，或到 globalTimeoutMs 就不等了继续往下走
            CompletableFuture<Void> allOf = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));
            try {
                allOf.get(globalTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // 超时不报错，记录日志后继续用已完成的结果
                long timedOut = futures.stream().filter(f -> !f.isDone()).count();
                log.warn("[Bid] {}/{} dsps timed out after {}ms", timedOut, futures.size(), globalTimeoutMs);

                // futures 和 dsps 是同一个 for 循环按顺序生成的，下标一一对应；
                // 找出还没 isDone() 的，按 dsp_id 打 timeout 计数
                for (int i = 0; i < dsps.size(); i++) {
                    if (!futures.get(i).isDone()) {
                        meterRegistry.counter("ssp_dsp_bid_total",
                                "dsp_id", dsps.get(i).getDspId(), "result", "timeout").increment();
                    }
                }
            }

            // 5. 收集结果并过滤
            // allResults：所有在超时前完成的 DSP 结果（含未出价、出价低于底价的），用于写 bid_log
            List<DspBidResult> allResults = futures.stream()
                    .filter(CompletableFuture::isDone)  // 超时未完成的直接丢弃
                    .map(CompletableFuture::join)        // 从 Future 里取出 DspBidResult
                    .filter(r -> r != null)
                    .toList();

            // results：从 allResults 里筛选出有效出价且高于底价的，用于选赢家
            List<DspBidResult> results = allResults.stream()
                    .filter(r -> r.getResponse() != null && r.getResponse().isValid())  // 出价 > 0
                    .filter(r -> r.getResponse().getBidPrice().compareTo(adSlot.getFloorPrice()) >= 0)  // 出价 >= 底价
                    .toList();

            // 6. 选出最高出价
            DspBidResult winner = results.stream()
                    .max(Comparator.comparing(r -> r.getResponse().getBidPrice()))
                    .orElse(null);

            String winnerId = winner != null ? winner.getDspId() : null;

            // 7. 有赢家时按计价策略(一价/二价)算中标价：传入所有有效出价(降序)和底价。
            //    提前算好，既用于写 bid_log，也用于构造响应；no fill 时为 null。
            BigDecimal winPrice = null;
            if (winner != null) {
                List<BigDecimal> sortedBids = results.stream()
                        .map(r -> r.getResponse().getBidPrice())
                        .sorted(Comparator.reverseOrder())
                        .toList();
                winPrice = pricingStrategy.computeWinPrice(sortedBids, adSlot.getFloorPrice());
            }

            // 8. 落库本次竞价的 bid_log(直写 or Kafka 由 BidLogWriter 决定)：传赢家 dspId 标记 win，成交价写到中标那条
            writeBidLogs(request.getRequestId(), request.getAdSlotId(), allResults, winnerId, winPrice);

            if (winner == null) {
                meterRegistry.counter("ssp_bid_requests_total", "result", "no_fill").increment();
                return null;  // no fill
            }

            BidResponse bidResponse = buildBidResponse(request, winner, winPrice);

            // 8. 把竞价结果存进 Redis，供后续曝光/点击埋点查询
            trackService.saveBidResult(bidResponse);

            meterRegistry.counter("ssp_bid_requests_total", "result", "fill").increment();
            return bidResponse;
        } finally {
            sample.stop(meterRegistry.timer("ssp_bid_duration_seconds"));
        }
    }

    // ---------- 私有方法 ----------

    private DspBidRequest buildDspRequest(BidRequest request, AdSlot slot, DspConfig dsp) {
        DspBidRequest dspReq = new DspBidRequest();
        dspReq.setRequestId(request.getRequestId());
        dspReq.setSlotId(slot.getSlotId());
        dspReq.setFloorPrice(slot.getFloorPrice());
        dspReq.setDevice(request.getDevice());
        dspReq.setUser(request.getUser());
        return dspReq;
    }

    private DspBidResult callDsp(DspConfig dsp, DspBidRequest dspRequest) {
        // 调用前先做 QPS 限流检查，被限流则直接跳过，不真正请求 DSP
        if (!rateLimiter.tryAcquire(dsp.getDspId(), dsp.getQpsLimit())) {
            return DspBidResult.rateLimited(dsp.getDspId());
        }

        long dspStart = System.currentTimeMillis();
        try {
            DspBidResponse response = dspCaller.bid(dsp, dspRequest);
            // 从发出请求到收到 DSP 响应的耗时（毫秒），用于写入 bid_log 做性能分析
            int elapsedMs = (int) (System.currentTimeMillis() - dspStart);
            meterRegistry.timer("ssp_dsp_call_duration_seconds", "dsp_id", dsp.getDspId())
                    .record(Duration.ofMillis(elapsedMs));
            return DspBidResult.success(dsp.getDspId(), response, elapsedMs);
        } catch (Exception e) {
            int elapsedMs = (int) (System.currentTimeMillis() - dspStart);
            meterRegistry.timer("ssp_dsp_call_duration_seconds", "dsp_id", dsp.getDspId())
                    .record(Duration.ofMillis(elapsedMs));
            log.error("[Bid] callDsp {} failed: {}", dsp.getDspId(), e.getMessage());
            return DspBidResult.error(dsp.getDspId(), elapsedMs);
        }
    }



    /**
     * 拼装本次竞价的一批 bid_log，交给 BidLogWriter 落库（直写 or Kafka 由配置决定）。
     */
    private void writeBidLogs(String requestId, String slotId, List<DspBidResult> results, String winnerId, BigDecimal winPrice) {
        List<BidLog> logs = new ArrayList<>();
        for (DspBidResult result : results) {
            BidLog bidLog = new BidLog();
            bidLog.setRequestId(requestId);
            bidLog.setSlotId(slotId);
            bidLog.setDspId(result.getDspId());
            bidLog.setResponseTimeMs(result.getResponseTimeMs());
            bidLog.setCreatedAt(LocalDateTime.now());

            if (result.getResponse() != null && result.getResponse().isValid()) {
                bidLog.setBidPrice(result.getResponse().getBidPrice());
                bidLog.setStatus(1);  // 有效出价
            } else {
                bidLog.setBidPrice(BigDecimal.ZERO);
                if (result.isRateLimited()) {
                    bidLog.setStatus(4);  // 限流
                } else {
                    bidLog.setStatus(result.isError() ? 3 : 2);  // 异常 or 无出价
                }
            }
            // 判断当前 DSP 是否是本次竞价的赢家
            boolean isWinner = result.getDspId().equals(winnerId);
            bidLog.setWin(isWinner ? 1 : 0);
            // 成交价只写到中标那条记录（二价下 winPrice ≠ 该 DSP 的 bidPrice）
            bidLog.setWinPrice(isWinner ? winPrice : null);

            // 按 (dsp_id, result) 维度统计每个 DSP 的出价结果
            meterRegistry.counter("ssp_dsp_bid_total", "dsp_id", result.getDspId(),
                    "result", dspResultTag(bidLog.getStatus(), isWinner)).increment();

            logs.add(bidLog);
        }
        // 落库策略：DirectBidLogWriter 同步单条 / KafkaBidLogWriter 发 Kafka 由消费者批量入库
        bidLogWriter.write(logs);
    }

    // 把 bid_log 的 status/win 映射成 Metrics 的 result tag：win/lose/no_bid/error/rate_limited
    private String dspResultTag(int status, boolean isWinner) {
        if (status == 1) return isWinner ? "win" : "lose";
        if (status == 4) return "rate_limited";
        return status == 3 ? "error" : "no_bid";
    }

    private BidResponse buildBidResponse(BidRequest request, DspBidResult winner, BigDecimal winPrice) {
        DspBidResponse dspResp = winner.getResponse();
        BidResponse response = new BidResponse();
        response.setRequestId(request.getRequestId());
        response.setAdSlotId(request.getAdSlotId());
        // userId 随结果缓存进 Redis，供曝光埋点做频次计数
        response.setUserId(request.getUser() != null ? request.getUser().getUserId() : null);
        response.setWinDsp(winner.getDspId());
        // winPrice 由计价策略决定(一价=出价本身；二价=第二高+增量)
        response.setWinPrice(winPrice);

        AdContentDTO content = new AdContentDTO();
        content.setTitle(dspResp.getTitle());
        content.setDescription(dspResp.getDescription());
        content.setImageUrl(dspResp.getImageUrl());
        content.setClickUrl(dspResp.getClickUrl());
        content.setImpressionTrackUrl("/api/v1/track/impression?rid=" + request.getRequestId());
        content.setClickTrackUrl("/api/v1/track/click?rid=" + request.getRequestId());
        response.setAdContent(content);

        return response;
    }

    // ---------- 内部结果类 ----------

    @lombok.Data
    private static class DspBidResult {
        private String dspId;
        private DspBidResponse response;
        private int responseTimeMs;
        private boolean error;
        private boolean rateLimited;

        static DspBidResult success(String dspId, DspBidResponse response, int ms) {
            DspBidResult r = new DspBidResult();
            r.dspId = dspId;
            r.response = response;
            r.responseTimeMs = ms;
            return r;
        }

        static DspBidResult error(String dspId) {
            return error(dspId, 0);
        }

        static DspBidResult error(String dspId, int ms) {
            DspBidResult r = new DspBidResult();
            r.dspId = dspId;
            r.responseTimeMs = ms;
            r.error = true;
            return r;
        }

        static DspBidResult rateLimited(String dspId) {
            DspBidResult r = new DspBidResult();
            r.dspId = dspId;
            r.responseTimeMs = 0;
            r.rateLimited = true;
            return r;
        }
    }
}
