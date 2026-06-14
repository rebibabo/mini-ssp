package com.example.ssp.service.dsp;

import com.example.ssp.model.dto.DspBidRequest;
import com.example.ssp.model.dto.DspBidResponse;
import com.example.ssp.model.entity.DspConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * 真实 HTTP 调用实现（Mode B）。
 *
 * 当 {@code ssp.dsp.mode=http} 时启用。用 WebClient 向 DSP 的 bidUrl POST 竞价请求，
 * 设置每个 DSP 独立的超时（{@code DspConfig.timeoutMs}），完整覆盖 HTTP 客户端、
 * 超时处理、异常容错的练习目标。
 *
 * <p>说明：BidService 的 callDsp 运行在 bidExecutor 线程池里，本身是同步语义，
 * 所以这里用 {@code block(timeout)} 把 WebClient 的异步 Mono 转成同步结果。
 * 超时或网络错误时抛异常，由 BidService.callDsp 的 try-catch 兜底成 error 结果，
 * 不影响其他 DSP。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
// 直接从配置文件中读取，这个配置项的值"等于"它时,才注册这个 Bean
@ConditionalOnProperty(name = "ssp.dsp.mode", havingValue = "http")
public class DspBidClient implements DspCaller {

    private final WebClient webClient;

    // DSP 没单独配 timeoutMs 时用的兜底超时（毫秒）
    private static final long DEFAULT_TIMEOUT_MS = 150L;

    @Override
    public DspBidResponse bid(DspConfig dsp, DspBidRequest request) {
        long timeoutMs = (dsp.getTimeoutMs() != null && dsp.getTimeoutMs() > 0)
                ? dsp.getTimeoutMs()
                : DEFAULT_TIMEOUT_MS;

        // POST {bidUrl}，请求体 = DspBidRequest(JSON)，响应体反序列化成 DspBidResponse
        // .timeout(...)：超过该时长 Mono 抛 TimeoutException
        // .block()：阻塞当前(线程池)线程直到拿到结果，把异步转同步
        return webClient.post()
                .uri(dsp.getBidUrl())
                .bodyValue(request)
                .retrieve()
                .bodyToMono(DspBidResponse.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .block(Duration.ofMillis(timeoutMs + 50));  // block 兜底超时，略大于业务超时
    }
}
