package com.example.ssp.service.bidlog;

import com.example.ssp.model.entity.BidLog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 压测专用：跳过 bid_log 写入，让结果只反映竞价主链路。
 */
@Component
@ConditionalOnProperty(name = "ssp.bid-log.mode", havingValue = "none")
public class NoopBidLogWriter implements BidLogWriter {

    @Override
    public void write(List<BidLog> logs) {
        // intentionally no-op
    }
}
