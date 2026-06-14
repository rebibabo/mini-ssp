package com.example.mockdsp;

import com.example.mockdsp.dto.DspBidRequest;
import com.example.mockdsp.dto.DspBidResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock DSP 竞价接口：SSP 通过 WebClient POST 到这里。
 * 行为（延迟/出价区间/不出价率/异常率/超时率）由 {@link DspBehaviorProperties} 配置驱动。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class DspBidController {

    private final DspBehaviorProperties props;

    @PostMapping("/bid")
    public DspBidResponse bid(@RequestBody DspBidRequest request) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        // 1. 模拟正常网络延迟
        sleep(randomBetween(props.getLatencyMinMs(), props.getLatencyMaxMs()));

        // 2. 模拟超时：额外睡一个超长时间，让 SSP 端超时丢弃本 DSP
        if (rnd.nextDouble() < props.getTimeoutRate()) {
            log.info("[{}] 模拟超时 requestId={}", props.getName(), request.getRequestId());
            sleep(props.getTimeoutSleepMs());
        }

        // 3. 模拟异常：返回 HTTP 500
        if (rnd.nextDouble() < props.getErrorRate()) {
            log.info("[{}] 模拟异常 500 requestId={}", props.getName(), request.getRequestId());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "mock dsp error");
        }

        // 4. 模拟不出价
        if (rnd.nextDouble() < props.getNoBidRate()) {
            log.info("[{}] 不出价 requestId={}", props.getName(), request.getRequestId());
            return DspBidResponse.noBid();
        }

        // 5. 生成随机出价（分 → 元）
        int bidCents = randomBetween(props.getMinBidCents(), props.getMaxBidCents());
        BigDecimal bidPrice = BigDecimal.valueOf(bidCents).movePointLeft(2);

        // 低于底价则不出价
        if (request.getFloorPrice() != null && bidPrice.compareTo(request.getFloorPrice()) < 0) {
            log.info("[{}] 出价 {} 低于底价 {}，不出价", props.getName(), bidPrice, request.getFloorPrice());
            return DspBidResponse.noBid();
        }

        DspBidResponse resp = new DspBidResponse();
        resp.setBidPrice(bidPrice);
        resp.setTitle("来自 " + props.getName() + " 的广告");
        resp.setDescription("优质广告内容");
        resp.setImageUrl("https://mock-cdn.example.com/" + props.getName() + "/banner.jpg");
        resp.setClickUrl("https://mock-landing.example.com/" + props.getName());

        log.info("[{}] 出价 {} requestId={}", props.getName(), bidPrice, request.getRequestId());
        return resp;
    }

    private int randomBetween(int min, int max) {
        if (max <= min) return min;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
