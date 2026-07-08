package com.example.ssp.consumer;

import com.example.ssp.mapper.BidLogMapper;
import com.example.ssp.model.entity.BidLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * bid_log 消费者：从 Kafka 批量拉取竞价日志，一次性批量入库。
 *
 * <p>因为 application.yml 配了 {@code spring.kafka.listener.type=batch}，
 * 监听方法的入参直接是 {@code List<BidLog>}（一次回调拿到一批），
 * 调用 {@code insertBatch} 一条 SQL 插多行，大幅减少 DB 往返。</p>
 *
 * <p>offset 提交：默认 BATCH 模式——方法正常返回后才提交 offset；
 * 若 insertBatch 抛异常、offset 不提交，这批消息会被重新投递（至少一次语义）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ssp.bid-log.mode", havingValue = "kafka")  // 仅 kafka 模式启用，direct 模式下不连 Kafka
public class BidLogConsumer {

    private final BidLogMapper bidLogMapper;

    @Value("${ssp.trace.timeline-enabled:false}")
    private boolean timelineTraceEnabled;

    @KafkaListener(topics = "${ssp.kafka.bid-log-topic:bid-log}")
    public void consume(List<BidLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return;
        }
        long startNs = System.nanoTime();
        bidLogMapper.insertBatch(logs);
        if (timelineTraceEnabled) {
            log.info("[Timeline] requestId={} stage=\"kafka consumer inserted bid_log to MySQL\" count={} stepMs={}",
                    logs.get(0).getRequestId(), logs.size(), nanosToMillis(System.nanoTime() - startNs));
        }
        log.debug("[BidLogConsumer] 批量入库 {} 条 bid_log", logs.size());
    }

    private double nanosToMillis(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }
}
