package com.example.ssp.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RateLimiter 纯单元测试：mock 掉 RedisTemplate，不依赖真实 Redis。
 * 验证限流计数判定、首次设 TTL、不限流短路等逻辑。
 *
 *   ┌──────────────────────────────────────────┬────────────────────────────────┐
 *   │                   测试                    │             验证点              │
 *   ├──────────────────────────────────────────┼────────────────────────────────┤
 *   │ qpsLimitZero_alwaysAllow_noRedisCall     │ qpsLimit=0 不限流,且根本不碰      │
 *   │                                          │ Redis                          │
 *   ├──────────────────────────────────────────┼────────────────────────────────┤
 *   │ qpsLimitNull_alwaysAllow_noRedisCall     │ qpsLimit=null 同上              │
 *   ├──────────────────────────────────────────┼────────────────────────────────┤
 *   │ firstRequest_returnsOne_setsTtlAndAllows │ 首次(INCR=1)放行,并设 TTL=2秒     │
 *   ├──────────────────────────────────────────┼────────────────────────────────┤
 *   │ countWithinLimit_allows_noTtlReset       │ 未超限放行,非首次不重设 TTL        │
 *   ├──────────────────────────────────────────┼────────────────────────────────┤
 *   │ countEqualsLimit_stillAllows             │ 边界 count==limit 仍放行         │
 *   ├──────────────────────────────────────────┼────────────────────────────────┤
 *   │ countExceedsLimit_rejected               │ 超限(11>10)被限流                │
 *   └──────────────────────────────────────────┴────────────────────────────────┘
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    // opsForValue() 返回的对象也要 mock，因为 increment 是两层调用
    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private RateLimiter rateLimiter;

    // 让 redisTemplate.opsForValue() 返回 mock 的 valueOperations
    // 只有真正会访问 Redis 的测试才调用，避免"多余 stub"告警
    private void stubOpsForValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void qpsLimitZero_alwaysAllow_noRedisCall() {
        // qpsLimit=0 表示不限流，应直接放行且不碰 Redis
        boolean allowed = rateLimiter.tryAcquire("dsp-001", 0);

        assertThat(allowed).isTrue();
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void qpsLimitNull_alwaysAllow_noRedisCall() {
        boolean allowed = rateLimiter.tryAcquire("dsp-001", null);

        assertThat(allowed).isTrue();
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void firstRequest_returnsOne_setsTtlAndAllows() {
        stubOpsForValue();
        // 模拟这一秒第一次请求：INCR 返回 1
        when(valueOperations.increment(anyString())).thenReturn(1L);

        boolean allowed = rateLimiter.tryAcquire("dsp-001", 10);

        assertThat(allowed).isTrue();
        // 第一次（count==1）应当给 key 设置 TTL=2 秒
        verify(redisTemplate).expire(anyString(), eq(2L), eq(TimeUnit.SECONDS));
    }

    @Test
    void countWithinLimit_allows_noTtlReset() {
        stubOpsForValue();
        // 这一秒已是第 5 次请求，未超 limit=10
        when(valueOperations.increment(anyString())).thenReturn(5L);

        boolean allowed = rateLimiter.tryAcquire("dsp-001", 10);

        assertThat(allowed).isTrue();
        // 非首次（count!=1）不应再设 TTL
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any());
    }

    @Test
    void countEqualsLimit_stillAllows() {
        stubOpsForValue();
        // 边界：count == limit 仍放行（条件是 count <= qpsLimit）
        when(valueOperations.increment(anyString())).thenReturn(10L);

        boolean allowed = rateLimiter.tryAcquire("dsp-001", 10);

        assertThat(allowed).isTrue();
    }

    @Test
    void countExceedsLimit_rejected() {
        stubOpsForValue();
        // 这一秒第 11 次，超过 limit=10，应被限流
        when(valueOperations.increment(anyString())).thenReturn(11L);

        boolean allowed = rateLimiter.tryAcquire("dsp-001", 10);

        assertThat(allowed).isFalse();
    }
}
