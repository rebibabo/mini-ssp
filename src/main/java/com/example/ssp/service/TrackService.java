package com.example.ssp.service;

import com.example.ssp.cache.FrequencyCapService;
import com.example.ssp.mapper.EventLogMapper;
import com.example.ssp.model.dto.BidResponse;
import com.example.ssp.model.entity.EventLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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

    /**
     * 竞价成功后，把结果存入 Redis，供后续埋点回调查询
     */
    public void saveBidResult(BidResponse bidResponse) {
        String key = KEY_BID_RESULT + bidResponse.getRequestId();
        // TTL 5分钟，广告展示和点击一般在竞价后几秒内发生
        redisTemplate.opsForValue().set(key, bidResponse, bidResultTtlMinutes, TimeUnit.MINUTES);
        log.debug("[Track] saved bid result, requestId={}", bidResponse.getRequestId());
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
}
