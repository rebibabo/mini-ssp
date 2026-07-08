package com.example.ssp.service.bidlog;

import com.example.ssp.model.dto.BidLogBatchMessage;
import com.example.ssp.model.entity.BidLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Kafka 批量消息实现：一次 bid 只发送一条 Kafka 消息，消息内包含本次竞价的所有 DSP 日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ssp.bid-log.mode", havingValue = "kafka-batch")
public class KafkaBatchBidLogWriter implements BidLogWriter {

    private final KafkaTemplate<String, BidLogBatchMessage> kafkaTemplate;

    @Value("${ssp.kafka.bid-log-batch-topic:bid-log-batch}")
    private String bidLogBatchTopic;

    @Value("${ssp.trace.timeline-enabled:false}")
    private boolean timelineTraceEnabled;

    @Override
    public void write(List<BidLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return;
        }
        String requestId = logs.get(0).getRequestId();
        kafkaTemplate.send(bidLogBatchTopic, requestId, new BidLogBatchMessage(requestId, logs));
        if (timelineTraceEnabled) {
            log.info("[Timeline] requestId={} stage=\"kafka producer accepted bid_log batch\" count={}",
                    requestId, logs.size());
        }
    }
}
