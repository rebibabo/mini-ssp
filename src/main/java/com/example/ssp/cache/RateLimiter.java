package com.example.ssp.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的固定窗口（按秒）QPS 限流器
 *
 * key 里带"精确到秒的时间戳"，每进入新的一秒就是一个全新的 key，
 * 自动实现"每秒重新计数"；TTL=2秒用于清理用过的旧 key。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiter {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String KEY_PREFIX = "ssp:rate:";
    private static final DateTimeFormatter SECOND_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final long TTL_SECONDS = 2;

    /**
     * 尝试为某个 DSP 获取一次调用许可
     *
     * @param dspId    DSP 标识
     * @param qpsLimit 每秒最大请求数，0 表示不限流
     * @return true=放行，false=被限流
     */
    public boolean tryAcquire(String dspId, Integer qpsLimit) {
        // qpsLimit 为 null 或 0 表示不限流，直接放行
        if (qpsLimit == null || qpsLimit <= 0) {
            return true;
        }

        // key = ssp:rate:{dspId}:{yyyyMMddHHmmss}，精确到秒，每秒一个新 key
        String key = KEY_PREFIX + dspId + ":" + LocalDateTime.now().format(SECOND_FMT);

        // INCR：原子 +1，返回累加后的值；key 不存在时从 0 开始（首次返回 1）
        Long count = redisTemplate.opsForValue().increment(key);

        // 第一次创建这个 key 时（返回 1）设置过期时间，避免旧 key 堆积
        if (count != null && count == 1) {
            redisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);
        }

        boolean allowed = count != null && count <= qpsLimit;
        if (!allowed) {
            log.warn("[RateLimit] dsp {} rejected, count={} > limit={}", dspId, count, qpsLimit);
        }
        return allowed;
    }
}
