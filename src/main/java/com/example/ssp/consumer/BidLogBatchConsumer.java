package com.example.ssp.consumer;

import com.example.ssp.mapper.BidLogMapper;
import com.example.ssp.model.dto.BidLogBatchMessage;
import com.example.ssp.model.entity.BidLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * bid_log 批量消息消费者：Kafka 中每条消息代表一次竞价，内部包含多条 DSP 日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ssp.bid-log.mode", havingValue = "kafka-batch")
public class BidLogBatchConsumer {

    private final BidLogMapper bidLogMapper;

    @Value("${ssp.trace.timeline-enabled:false}")
    private boolean timelineTraceEnabled;

    @KafkaListener(
            topics = "${ssp.kafka.bid-log-batch-topic:bid-log-batch}",
            groupId = "${ssp.kafka.bid-log-batch-group:ssp-bidlog-batch-consumer}")
    public void consume(List<BidLogBatchMessage> batches) {
        if (batches == null || batches.isEmpty()) {
            return;
        }

        List<BidLog> logs = new ArrayList<>();
        for (BidLogBatchMessage batch : batches) {
            if (batch.getLogs() != null && !batch.getLogs().isEmpty()) {
                logs.addAll(batch.getLogs());
            }
        }
        if (logs.isEmpty()) {
            return;
        }

        long startNs = System.nanoTime();
        bidLogMapper.insertBatch(logs);
        if (timelineTraceEnabled) {
            log.info("[Timeline] requestId={} stage=\"kafka batch consumer inserted bid_log to MySQL\" batches={} count={} stepMs={}",
                    logs.get(0).getRequestId(), batches.size(), logs.size(), nanosToMillis(System.nanoTime() - startNs));
        }
        log.debug("[BidLogBatchConsumer] 批量入库 {} 批 / {} 条 bid_log", batches.size(), logs.size());
    }

    private double nanosToMillis(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }
}
