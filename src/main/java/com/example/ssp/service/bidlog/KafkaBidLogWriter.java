package com.example.ssp.service.bidlog;

import com.example.ssp.model.entity.BidLog;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Kafka 实现：把 bid_log 发到 topic，由 BidLogConsumer 批量入库。
 *
 * {@code ssp.bid-log.mode=kafka} 时才注册（同时 BidLogConsumer 也只在该模式注册，
 * 这样 direct 模式下完全不碰 Kafka、不刷连接日志）。
 *
 * <p>send 非阻塞（只入 producer 缓冲，客户端后台线程发送），不占竞价线程。
 * key 用 requestId：同一次竞价的多条进同一分区，保证有序、便于排查。</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ssp.bid-log.mode", havingValue = "kafka")
public class KafkaBidLogWriter implements BidLogWriter {

    private final KafkaTemplate<String, BidLog> kafkaTemplate;

    @Value("${ssp.kafka.bid-log-topic:bid-log}")
    private String bidLogTopic;

    @Override
    public void write(List<BidLog> logs) {
        for (BidLog log : logs) {
            kafkaTemplate.send(bidLogTopic, log.getRequestId(), log);
        }
    }
}
