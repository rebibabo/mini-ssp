package com.example.ssp.service.dsp;

import com.example.ssp.model.dto.DspBidRequest;
import com.example.ssp.model.dto.DspBidResponse;
import com.example.ssp.model.entity.DspConfig;

/**
 * DSP 竞价调用策略接口。
 *
 * BidService 只依赖这个接口，不关心底层是"进程内模拟"还是"真实 HTTP 调用"。
 * 通过配置 {@code ssp.dsp.mode} 选择具体实现：
 * <ul>
 *   <li>{@code mock}（默认）→ {@link MockDspCaller}，进程内调用 MockDspHandler，无依赖</li>
 *   <li>{@code http} → {@link DspBidClient}，用 WebClient 真实 HTTP 调用独立的 Mock DSP 服务</li>
 * </ul>
 * 这是典型的"策略模式"：同一个动作（向 DSP 竞价）有多种实现，运行时按配置切换。
 */
public interface DspCaller {

    /**
     * 向指定 DSP 发起一次竞价。
     *
     * @param dsp     DSP 配置（含 bidUrl、timeoutMs 等，HTTP 模式需要）
     * @param request 竞价请求（含底价、设备、用户信息）
     * @return DSP 的出价响应；不出价时 bidPrice 为 0
     */
    DspBidResponse bid(DspConfig dsp, DspBidRequest request);
}
