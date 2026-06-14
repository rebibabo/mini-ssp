package com.example.ssp.service.bidlog;

import com.example.ssp.mapper.BidLogMapper;
import com.example.ssp.model.entity.BidLog;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 直写实现（默认）：同步逐条 insert 到 MySQL，不依赖 Kafka/Docker。
 *
 * {@code matchIfMissing = true}：没配 ssp.bid-log.mode 时也用这个，作为默认。
 *
 * <p>注意：write 在调用方（竞价线程）上同步执行，N 个 DSP 就是 N 次 insert、N 次 DB 往返，
 * 会给竞价响应加上这部分耗时——这正是和 Kafka 异步批量方案做对比时要观察的点。</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ssp.bid-log.mode", havingValue = "direct", matchIfMissing = true)
public class DirectBidLogWriter implements BidLogWriter {

    private final BidLogMapper bidLogMapper;

    @Override
    public void write(List<BidLog> logs) {
        for (BidLog log : logs) {
            bidLogMapper.insert(log);   // 单条 insert
        }
    }
}
