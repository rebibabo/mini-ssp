package com.example.ssp.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 频次控制：限制同一用户对同一 DSP 广告每天的展示次数。
 *
 * <p>和 {@link RateLimiter} 同样是 Redis 固定窗口，只是粒度从「秒」变成「天」：
 * key 里带日期，进入新的一天就是全新 key，自动清零。</p>
 *
 * <p>两个动作分开调用：<b>检查</b>在竞价时({@link #isCapped})筛掉看够的 DSP；
 * <b>计数</b>在曝光时({@link #increment})对(用户,DSP)+1。</p>
 *
 * <p>用 {@link StringRedisTemplate}：计数器是纯数字字符串，INCR/GET 都直接，
 * 不走 JSON 序列化，避免类型转换的麻烦。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FrequencyCapService {

    private final StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "ssp:freq:";
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    // key 带日期，TTL 给到 25 小时：覆盖当天 + 跨午夜的缓冲，过后自动清理
    private static final long TTL_HOURS = 25;

    /**
     * 检查(用户,DSP)今天的展示次数是否已达上限。
     *
     * @param dailyCap 每日上限，<=0 表示不限制
     * @return true=已达上限(该剔除)，false=还能展示
     */
    public boolean isCapped(String userId, String dspId, int dailyCap) {
        if (dailyCap <= 0) {
            return false;  // 不限制
        }
        String key = buildKey(userId, dspId);
        String val = stringRedisTemplate.opsForValue().get(key);
        long count = (val == null) ? 0 : Long.parseLong(val);
        return count >= dailyCap;
    }

    /**
     * 用户看到一次广告：对(用户,DSP)今日计数 +1。曝光埋点时调用。
     */
    public void increment(String userId, String dspId) {
        String key = buildKey(userId, dspId);
        // INCR 原子 +1，key 不存在时首次返回 1
        Long count = stringRedisTemplate.opsForValue().increment(key);
        // 首次创建该 key 时设 TTL，避免旧 key 永久堆积
        if (count != null && count == 1) {
            stringRedisTemplate.expire(key, TTL_HOURS, TimeUnit.HOURS);
        }
    }

    // key = ssp:freq:{userId}:{dspId}:{yyyyMMdd}
    private String buildKey(String userId, String dspId) {
        return KEY_PREFIX + userId + ":" + dspId + ":" + LocalDate.now().format(DAY_FMT);
    }
}
