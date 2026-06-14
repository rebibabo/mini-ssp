package com.example.ssp.benchmark;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ssp.mapper.BidLogMapper;
import com.example.ssp.model.entity.BidLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * bid_log 写入微基准：对比「循环单条 insert」与「一次 insertBatch」的耗时。
 *
 * 手动跑：./mvnw test -Dtest=BidLogInsertBenchmarkTest
 * 需要真实 MySQL（不开事务，真实 commit 才能测到真实写入耗时）。
 * 所有造的数据用 request_id 前缀 "bench-" 标记，跑完自动清理。
 */
@SpringBootTest
class BidLogInsertBenchmarkTest {

    private static final String MARK_PREFIX = "bench-";
    private static final int[] SIZES = {100, 500, 1000};

    @Autowired
    private BidLogMapper bidLogMapper;

    @AfterEach
    void cleanup() {
        deleteBenchRows();
    }

    @Test
    void compareSingleVsBatch() {
        // 预热：JIT 编译 + 连接池建连，避免第一次的冷启动干扰测量
        warmup();
        deleteBenchRows();

        System.out.println("\n==== bid_log 写入耗时对比（单条 insert vs 批量 insertBatch）====");
        System.out.printf("%-8s %-16s %-16s %-10s%n", "N", "单条(ms)", "批量(ms)", "提速倍数");

        for (int n : SIZES) {
            // 单条：循环 insert
            List<BidLog> single = build(n, MARK_PREFIX + "single-");
            long t1 = timeMs(() -> single.forEach(bidLogMapper::insert));

            // 批量：一条 SQL 多行
            List<BidLog> batch = build(n, MARK_PREFIX + "batch-");
            long t2 = timeMs(() -> bidLogMapper.insertBatch(batch));

            double speedup = t2 == 0 ? 0 : (double) t1 / t2;
            System.out.printf("%-8d %-16d %-16d %-10s%n", n, t1, t2, String.format("%.1fx", speedup));

            deleteBenchRows();  // 每档之间清理，保持表状态一致
        }
        System.out.println("================================================================\n");
    }

    // ---------- 工具 ----------

    private void warmup() {
        bidLogMapper.insertBatch(build(50, MARK_PREFIX + "warmup-"));
        build(50, MARK_PREFIX + "warmup-").forEach(bidLogMapper::insert);
    }

    /** 造 n 条 BidLog，request_id 带前缀便于清理 */
    private List<BidLog> build(int n, String ridPrefix) {
        List<BidLog> list = new ArrayList<>(n);
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < n; i++) {
            BidLog b = new BidLog();
            b.setRequestId(ridPrefix + i);
            b.setSlotId("slot-bench");
            b.setDspId("dsp-001");
            b.setBidPrice(new BigDecimal("2.50"));
            b.setResponseTimeMs(50);
            b.setStatus(1);
            b.setWin(0);
            b.setCreatedAt(now);
            list.add(b);
        }
        return list;
    }

    /** 执行 task 并返回耗时（毫秒） */
    private long timeMs(Runnable task) {
        long start = System.nanoTime();
        task.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    private void deleteBenchRows() {
        bidLogMapper.delete(new LambdaQueryWrapper<BidLog>().likeRight(BidLog::getRequestId, MARK_PREFIX));
    }
}
