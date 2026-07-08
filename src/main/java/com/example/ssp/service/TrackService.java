package com.example.ssp.service;

import com.example.ssp.cache.FrequencyCapService;
import com.example.ssp.mapper.EventLogMapper;
import com.example.ssp.model.dto.BidResponse;
import com.example.ssp.model.entity.EventLog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrackService {

    private final EventLogMapper eventLogMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final FrequencyCapService frequencyCapService;

    private static final String KEY_BID_RESULT = "ssp:bid_result:";

    // 事件类型常量，对应 event_log.event_type
    private static final int EVENT_IMPRESSION = 1;
    private static final int EVENT_CLICK = 2;

    @Value("${ssp.cache.bid-result-ttl-minutes:5}")
    private long bidResultTtlMinutes;

    @Value("${ssp.trace.timeline-enabled:false}")
    private boolean timelineTraceEnabled;

    @Value("${ssp.track.save-bid-result-mode:}")
    private String saveBidResultMode = "";

    @Value("${ssp.track.bid-result-batch-size:200}")
    private int bidResultBatchSize;

    @Value("${ssp.track.bid-result-flush-interval-ms:5}")
    private long bidResultFlushIntervalMs;

    @Value("${ssp.track.bid-result-queue-capacity:50000}")
    private int bidResultQueueCapacity;

    private volatile boolean batchWriterRunning;
    private BlockingQueue<BidResponse> bidResultQueue;
    private Thread batchWriterThread;

    /**
     * 竞价成功后，把结果存入 Redis，供后续埋点回调查询
     */
    public void saveBidResult(BidResponse bidResponse) {
        String key = KEY_BID_RESULT + bidResponse.getRequestId();
        long startNs = System.nanoTime();
        // TTL 5分钟，广告展示和点击一般在竞价后几秒内发生
        redisTemplate.opsForValue().set(key, bidResponse, bidResultTtlMinutes, TimeUnit.MINUTES);
        if (timelineTraceEnabled) {
            log.info("[Timeline] requestId={} stage=\"save bid_result to Redis\" stepMs={}",
                    bidResponse.getRequestId(), nanosToMillis(System.nanoTime() - startNs));
        }
        log.debug("[Track] saved bid result, requestId={}", bidResponse.getRequestId());
    }

    /**
     * 批量异步保存竞价结果：主线程只入队，后台线程用 Redis pipeline 批量 SET。
     * 队列满或后台未启动时回退同步写，优先保证追踪数据不丢。
     */
    public void saveBidResultBatchAsync(BidResponse bidResponse) {
        BlockingQueue<BidResponse> queue = bidResultQueue;
        if (!batchWriterRunning || queue == null || !queue.offer(bidResponse)) {
            log.warn("[Track] bid_result batch queue unavailable/full, fallback sync write, requestId={}",
                    bidResponse.getRequestId());
            saveBidResult(bidResponse);
        }
    }

    @PostConstruct
    public void startBidResultBatchWriter() {
        if (!"batch".equalsIgnoreCase(saveBidResultMode == null ? "" : saveBidResultMode.trim())) {
            return;
        }

        int capacity = Math.max(1, bidResultQueueCapacity);
        bidResultBatchSize = Math.max(1, bidResultBatchSize);
        bidResultFlushIntervalMs = Math.max(1, bidResultFlushIntervalMs);
        bidResultQueue = new ArrayBlockingQueue<>(capacity);
        batchWriterRunning = true;
        batchWriterThread = new Thread(this::runBidResultBatchWriter, "bid-result-batch-writer");
        batchWriterThread.setDaemon(true);
        batchWriterThread.start();
        log.info("[Track] bid_result batch writer started, batchSize={}, flushIntervalMs={}, queueCapacity={}",
                bidResultBatchSize, bidResultFlushIntervalMs, capacity);
    }

    @PreDestroy
    public void stopBidResultBatchWriter() {
        batchWriterRunning = false;
        Thread writer = batchWriterThread;
        if (writer != null) {
            writer.interrupt();
            try {
                writer.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flushRemainingBidResults();
    }

    /**
     * 处理曝光事件：查竞价结果 → 写 event_log
     * 返回 clickUrl，供点击追踪使用；requestId 无效时返回 null
     */
    public String handleImpression(String requestId, String ip, String ua) {
        BidResponse bidResponse = getBidResult(requestId);
        if (bidResponse == null) {
            log.warn("[Track] impression: bid result not found, requestId={}", requestId);
            return null;
        }

        // 写曝光记录
        saveEventLog(bidResponse, EVENT_IMPRESSION, ip, ua);

        // 频次计数：用户真正看到广告了，对(用户,中标DSP)今日 +1（匿名用户跳过）
        String userId = bidResponse.getUserId();
        if (userId != null && !userId.isBlank()) {
            frequencyCapService.increment(userId, bidResponse.getWinDsp());
        }

        log.info("[Track] impression recorded, requestId={}, dsp={}", requestId, bidResponse.getWinDsp());

        return bidResponse.getAdContent().getClickUrl();
    }

    /**
     * 处理点击事件：查竞价结果 → 写 event_log → 返回落地页 URL（用于 302 重定向）
     */
    public String handleClick(String requestId, String ip, String ua) {
        BidResponse bidResponse = getBidResult(requestId);
        if (bidResponse == null) {
            log.warn("[Track] click: bid result not found, requestId={}", requestId);
            return null;
        }

        // 写点击记录
        saveEventLog(bidResponse, EVENT_CLICK, ip, ua);
        log.info("[Track] click recorded, requestId={}, dsp={}", requestId, bidResponse.getWinDsp());

        return bidResponse.getAdContent().getClickUrl();
    }

    // ---------- 私有方法 ----------

    private void runBidResultBatchWriter() {
        List<BidResponse> batch = new ArrayList<>(bidResultBatchSize);
        while (batchWriterRunning || !bidResultQueue.isEmpty()) {
            try {
                BidResponse first = bidResultQueue.poll(bidResultFlushIntervalMs, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                bidResultQueue.drainTo(batch, bidResultBatchSize - 1);
                saveBidResultBatch(batch);
                batch.clear();
            } catch (InterruptedException e) {
                if (!batchWriterRunning) {
                    break;
                }
                log.warn("[Track] bid_result batch writer interrupted while running");
            } catch (Exception e) {
                log.error("[Track] bid_result batch writer failed: {}", e.getMessage(), e);
                saveBidResultBatchFallback(batch);
                batch.clear();
            }
        }
    }

    private void flushRemainingBidResults() {
        BlockingQueue<BidResponse> queue = bidResultQueue;
        if (queue == null || queue.isEmpty()) {
            return;
        }

        List<BidResponse> batch = new ArrayList<>(bidResultBatchSize);
        queue.drainTo(batch, bidResultBatchSize);
        while (!batch.isEmpty()) {
            saveBidResultBatch(batch);
            batch.clear();
            queue.drainTo(batch, bidResultBatchSize);
        }
    }

    private void saveBidResultBatch(List<BidResponse> batch) {
        long startNs = System.nanoTime();
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                for (BidResponse bidResponse : batch) {
                    String key = KEY_BID_RESULT + bidResponse.getRequestId();
                    operations.opsForValue().set(key, bidResponse, bidResultTtlMinutes, TimeUnit.MINUTES);
                }
                return null;
            }
        });
        if (timelineTraceEnabled) {
            log.info("[Timeline] stage=\"save bid_result batch to Redis\" count={} stepMs={}",
                    batch.size(), nanosToMillis(System.nanoTime() - startNs));
        }
        log.debug("[Track] saved bid result batch, count={}", batch.size());
    }

    private void saveBidResultBatchFallback(List<BidResponse> batch) {
        for (BidResponse bidResponse : batch) {
            try {
                saveBidResult(bidResponse);
            } catch (Exception e) {
                log.error("[Track] fallback save bid_result failed, requestId={}: {}",
                        bidResponse.getRequestId(), e.getMessage(), e);
            }
        }
    }

    /**
     * 从 Redis 查竞价结果，结果不存在（过期或无效 requestId）返回 null
     */
    private BidResponse getBidResult(String requestId) {
        String key = KEY_BID_RESULT + requestId;
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached == null) return null;

        // Redis 取出的是 LinkedHashMap，需要转换回 BidResponse
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        return mapper.convertValue(cached, BidResponse.class);
    }

    /**
     * 写入 event_log
     */
    private void saveEventLog(BidResponse bidResponse, int eventType, String ip, String ua) {
        EventLog log = new EventLog();
        log.setRequestId(bidResponse.getRequestId());
        log.setEventType(eventType);
        log.setSlotId(bidResponse.getAdSlotId());
        log.setDspId(bidResponse.getWinDsp());
        log.setWinPrice(bidResponse.getWinPrice());
        log.setIp(ip);
        log.setUa(ua);
        log.setCreatedAt(LocalDateTime.now());
        eventLogMapper.insert(log);
    }

    private double nanosToMillis(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }
}
