package com.example.ssp.controller;

import com.example.ssp.model.dto.BidRequest;
import com.example.ssp.model.dto.BidResponse;
import com.example.ssp.model.vo.ApiResponse;
import com.example.ssp.service.BidService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "竞价接口", description = "媒体/App 发起广告请求，SSP 向多个 DSP 竞价并返回最高出价广告")
@RestController
@RequestMapping("/api/v1/bid")
@RequiredArgsConstructor
public class BidController {

    private final BidService bidService;

    /**
     * 核心竞价接口，媒体/App 发起广告请求，SSP 返回竞价结果
     */
    @Operation(summary = "核心竞价", description = "并发向广告位关联的 DSP 竞价，选最高出价返回；无有效出价时返回 code=1（no fill）")
    @PostMapping
    public ApiResponse<BidResponse> bid(@RequestBody @Valid BidRequest request) {
        // TraceFilter 在请求最开始生成了一个 UUID 作为 traceId。
        // 但此时 JSON body 还没解析，Filter 拿不到业务 requestId。
        // Controller 解析完 body 后，用业务 requestId 覆盖，让 traceId 和业务 id 保持一致，
        // 这样 grep requestId 就能把整条链路的日志全捞出来。
        MDC.put("traceId", request.getRequestId());
        log.info("[Bid] requestId={}, slotId={}", request.getRequestId(), request.getAdSlotId());

        BidResponse response = bidService.processBid(request);

        // processBid 返回 null 表示 no fill（没有合适的广告）
        if (response == null) {
            log.info("[Bid] no fill, requestId={}", request.getRequestId());
            return ApiResponse.noFill();
        }

        log.info("[Bid] win dsp={}, price={}, requestId={}", response.getWinDsp(), response.getWinPrice(), request.getRequestId());
        return ApiResponse.success(response);
    }
}
