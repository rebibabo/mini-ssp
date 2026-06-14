package com.example.ssp.service.bidlog;

import com.example.ssp.model.entity.BidLog;

import java.util.List;

/**
 * bid_log 落库策略接口。
 *
 * BidService 算完竞价、拼好一批 BidLog 后，只调 {@link #write(List)}，
 * 不关心底层是「直接写 MySQL」还是「发 Kafka 由消费者批量入库」。
 * 通过配置 {@code ssp.bid-log.mode} 选择实现：
 * <ul>
 *   <li>{@code direct}（默认）→ DirectBidLogWriter：同步单条 insert，无需 Kafka/Docker</li>
 *   <li>{@code kafka} → KafkaBidLogWriter：发 Kafka，消费者批量 insertBatch</li>
 * </ul>
 * 和 DspCaller / PricingStrategy 一样是策略模式。
 */
public interface BidLogWriter {

    /**
     * 落库一次竞价产生的一批 bid_log（每个参与 DSP 一条）。
     *
     * @param logs 已拼装好的 BidLog 列表
     */
    void write(List<BidLog> logs);
}
