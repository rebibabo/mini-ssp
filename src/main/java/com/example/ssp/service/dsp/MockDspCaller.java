package com.example.ssp.service.dsp;

import com.example.ssp.model.dto.DspBidRequest;
import com.example.ssp.model.dto.DspBidResponse;
import com.example.ssp.model.entity.DspConfig;
import com.example.ssp.service.mock.MockDspHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 进程内模拟实现（Mode A，默认）。
 *
 * {@code @ConditionalOnProperty(matchIfMissing = true)}：当 {@code ssp.dsp.mode=mock}
 * 或没配这个属性时，注册这个 Bean。把 DspCaller 接口委托给已有的 {@link MockDspHandler}，
 * 不发真实 HTTP，零依赖，方便快速验证和无 DSP 服务时跑通核心链路。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ssp.dsp.mode", havingValue = "mock", matchIfMissing = true)
public class MockDspCaller implements DspCaller {

    private final MockDspHandler mockDspHandler;

    @Override
    public DspBidResponse bid(DspConfig dsp, DspBidRequest request) {
        return mockDspHandler.bid(dsp.getDspId(), request);
    }
}
