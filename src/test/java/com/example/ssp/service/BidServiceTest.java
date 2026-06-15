package com.example.ssp.service;

import com.example.ssp.cache.FrequencyCapService;
import com.example.ssp.cache.RateLimiter;
import com.example.ssp.cache.SlotCacheService;
import com.example.ssp.model.dto.BidRequest;
import com.example.ssp.model.dto.BidResponse;
import com.example.ssp.model.dto.DeviceDTO;
import com.example.ssp.model.dto.DspBidRequest;
import com.example.ssp.model.dto.DspBidResponse;
import com.example.ssp.model.entity.AdSlot;
import com.example.ssp.model.entity.DspConfig;
import com.example.ssp.service.bidlog.BidLogWriter;
import com.example.ssp.service.dsp.DspCaller;
import com.example.ssp.service.pricing.FirstPricePricing;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BidService 竞价决策逻辑单元测试。
 * mock 掉所有外部依赖（缓存、限流、MockDSP、Mapper、埋点），
 * 用同步执行器（Runnable::run）让并发竞价在同线程跑完，结果确定可断言。
 * 覆盖：无关联 DSP、多有效出价选最高、出价低于底价被过滤、全不出价 → no fill。
 */
@ExtendWith(MockitoExtension.class)
class BidServiceTest {

    @Mock
    private SlotCacheService slotCacheService;
    @Mock
    private TrackService trackService;
    @Mock
    private BidLogWriter bidLogWriter;
    @Mock
    private DspCaller dspCaller;
    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private FrequencyCapService frequencyCapService;

    private BidService bidService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        // bidExecutor 用同步执行器：supplyAsync 立即在当前线程执行，futures 直接完成
        // 现有用例断言的是一价行为(winPrice==最高出价)，传入 FirstPricePricing
        meterRegistry = new SimpleMeterRegistry();
        bidService = new BidService(slotCacheService, trackService, bidLogWriter,
                dspCaller, rateLimiter, frequencyCapService, new FirstPricePricing(), meterRegistry, Runnable::run);
        // 单元测试没有开spring容器，所以@Value 字段不会根据yml配置文件注入
        // 手动设置，这是springboot把反射包装成测试好用的工具
        // bidService.globalTimeoutMs=200
        ReflectionTestUtils.setField(bidService, "globalTimeoutMs", 200L);
    }

    @Test
    void shouldReturnNoFill_whenSlotHasNoDsp() {
        // 广告位存在，但没有关联任何 DSP
        when(slotCacheService.getSlot("slot-1")).thenReturn(buildSlot("slot-1", "1.0"));
        when(slotCacheService.getDspsForSlot("slot-1")).thenReturn(List.of());

        BidResponse response = bidService.processBid(buildRequest("slot-1"));

        assertThat(response).isNull();
        // 没有 DSP 就不该发起任何竞价
        verify(dspCaller, never()).bid(any(), any());
    }

    @Test
    void shouldIncrementNoFillCounter_whenSlotHasNoDsp() {
        when(slotCacheService.getSlot("slot-1")).thenReturn(buildSlot("slot-1", "1.0"));
        when(slotCacheService.getDspsForSlot("slot-1")).thenReturn(List.of());

        bidService.processBid(buildRequest("slot-1"));

        assertThat(meterRegistry.counter("ssp_bid_requests_total", "result", "no_fill").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldSelectHighestBid_whenMultipleValidBids() {
        when(slotCacheService.getSlot("slot-1")).thenReturn(buildSlot("slot-1", "1.0"));
        when(slotCacheService.getDspsForSlot("slot-1"))
                .thenReturn(List.of(buildDsp("dsp-A"), buildDsp("dsp-B")));
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(true);
        // dsp-A 出 3.5，dsp-B 出 2.8
        // DspCaller.bid 入参是 DspConfig，用 argThat 按 dspId 匹配
        when(dspCaller.bid(argThat(d -> d != null && "dsp-A".equals(d.getDspId())), any())).thenReturn(buildBid("3.5"));
        when(dspCaller.bid(argThat(d -> d != null && "dsp-B".equals(d.getDspId())), any())).thenReturn(buildBid("2.8"));

        BidResponse response = bidService.processBid(buildRequest("slot-1"));

        assertThat(response).isNotNull();
        assertThat(response.getWinDsp()).isEqualTo("dsp-A");
        assertThat(response.getWinPrice()).isEqualByComparingTo("3.5");
        // 中标后应缓存竞价结果供埋点查询
        verify(trackService).saveBidResult(response);
    }

    @Test
    void shouldIncrementDspBidCounters_withWinAndLoseTags() {
        when(slotCacheService.getSlot("slot-1")).thenReturn(buildSlot("slot-1", "1.0"));
        when(slotCacheService.getDspsForSlot("slot-1"))
                .thenReturn(List.of(buildDsp("dsp-A"), buildDsp("dsp-B")));
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(true);
        // dsp-A 出 3.5（赢家），dsp-B 出 2.8（输家）
        when(dspCaller.bid(argThat(d -> d != null && "dsp-A".equals(d.getDspId())), any())).thenReturn(buildBid("3.5"));
        when(dspCaller.bid(argThat(d -> d != null && "dsp-B".equals(d.getDspId())), any())).thenReturn(buildBid("2.8"));

        bidService.processBid(buildRequest("slot-1"));

        assertThat(meterRegistry.counter("ssp_dsp_bid_total", "dsp_id", "dsp-A", "result", "win").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("ssp_dsp_bid_total", "dsp_id", "dsp-B", "result", "lose").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldIncrementFillCounter_whenWin() {
        when(slotCacheService.getSlot("slot-1")).thenReturn(buildSlot("slot-1", "1.0"));
        when(slotCacheService.getDspsForSlot("slot-1")).thenReturn(List.of(buildDsp("dsp-A")));
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(true);
        when(dspCaller.bid(any(), any())).thenReturn(buildBid("3.5"));

        bidService.processBid(buildRequest("slot-1"));

        assertThat(meterRegistry.counter("ssp_bid_requests_total", "result", "fill").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldFilterOut_whenBidBelowFloorPrice() {
        // 底价 3.0，唯一的 DSP 只出 2.5 → 被过滤 → no fill
        when(slotCacheService.getSlot("slot-1")).thenReturn(buildSlot("slot-1", "3.0"));
        when(slotCacheService.getDspsForSlot("slot-1")).thenReturn(List.of(buildDsp("dsp-A")));
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(true);
        when(dspCaller.bid(argThat(d -> d != null && "dsp-A".equals(d.getDspId())), any())).thenReturn(buildBid("2.5"));

        BidResponse response = bidService.processBid(buildRequest("slot-1"));

        assertThat(response).isNull();
        verify(trackService, never()).saveBidResult(any());
    }

    @Test
    void shouldReturnNoFill_whenAllDspsNoBid() {
        // 模拟所有dsp都不出价，response返回null
        when(slotCacheService.getSlot("slot-1")).thenReturn(buildSlot("slot-1", "1.0"));
        when(slotCacheService.getDspsForSlot("slot-1"))
                .thenReturn(List.of(buildDsp("dsp-A"), buildDsp("dsp-B")));
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(true);
        // 两个 DSP 都不出价
        when(dspCaller.bid(any(), any())).thenReturn(DspBidResponse.noBid());

        BidResponse response = bidService.processBid(buildRequest("slot-1"));

        assertThat(response).isNull();
    }

    @Test
    void shouldIncrementDspBidCounters_withNoBidTag() {
        when(slotCacheService.getSlot("slot-1")).thenReturn(buildSlot("slot-1", "1.0"));
        when(slotCacheService.getDspsForSlot("slot-1"))
                .thenReturn(List.of(buildDsp("dsp-A"), buildDsp("dsp-B")));
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(true);
        // 两个 DSP 都不出价
        when(dspCaller.bid(any(), any())).thenReturn(DspBidResponse.noBid());

        bidService.processBid(buildRequest("slot-1"));

        assertThat(meterRegistry.counter("ssp_dsp_bid_total", "dsp_id", "dsp-A", "result", "no_bid").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("ssp_dsp_bid_total", "dsp_id", "dsp-B", "result", "no_bid").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldIncrementTimeoutCounter_whenDspExceedsGlobalTimeout() throws Exception {
        // 用真实线程池，让 dsp-B 的调用故意 sleep 超过 globalTimeoutMs，触发超时
        ExecutorService executor = Executors.newFixedThreadPool(2);
        BidService realBidService = new BidService(slotCacheService, trackService, bidLogWriter,
                dspCaller, rateLimiter, frequencyCapService, new FirstPricePricing(), meterRegistry, executor);
        ReflectionTestUtils.setField(realBidService, "globalTimeoutMs", 100L);

        when(slotCacheService.getSlot("slot-1")).thenReturn(buildSlot("slot-1", "1.0"));
        when(slotCacheService.getDspsForSlot("slot-1"))
                .thenReturn(List.of(buildDsp("dsp-A"), buildDsp("dsp-B")));
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(true);
        // dsp-A 正常快速返回
        when(dspCaller.bid(argThat(d -> d != null && "dsp-A".equals(d.getDspId())), any())).thenReturn(buildBid("3.5"));
        // dsp-B sleep 300ms，超过 100ms 的全局超时
        when(dspCaller.bid(argThat(d -> d != null && "dsp-B".equals(d.getDspId())), any())).thenAnswer(invocation -> {
            Thread.sleep(300);
            return buildBid("2.8");
        });

        try {
            realBidService.processBid(buildRequest("slot-1"));

            assertThat(meterRegistry.counter("ssp_dsp_bid_total", "dsp_id", "dsp-B", "result", "timeout").count())
                    .isEqualTo(1.0);
        } finally {
            executor.shutdown();
        }
    }

    // ---------- 工具方法 ----------

    private BidRequest buildRequest(String slotId) {
        BidRequest req = new BidRequest();
        req.setRequestId("req-test");
        req.setAdSlotId(slotId);
        DeviceDTO device = new DeviceDTO();
        device.setOs("iOS");
        req.setDevice(device);
        return req;
    }

    private AdSlot buildSlot(String slotId, String floorPrice) {
        AdSlot slot = new AdSlot();
        slot.setSlotId(slotId);
        slot.setFloorPrice(new BigDecimal(floorPrice));
        slot.setStatus(1);
        return slot;
    }

    private DspConfig buildDsp(String dspId) {
        DspConfig dsp = new DspConfig();
        dsp.setDspId(dspId);
        dsp.setQpsLimit(0);  // 不限流
        dsp.setStatus(1);
        return dsp;
    }

    private DspBidResponse buildBid(String price) {
        DspBidResponse resp = new DspBidResponse();
        resp.setBidPrice(new BigDecimal(price));
        resp.setTitle("ad");
        resp.setClickUrl("https://example.com");
        return resp;
    }
}
