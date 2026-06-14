package com.example.ssp.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 频次控制单元测试：mock StringRedisTemplate，精确验证「达上限拦截」「首次设 TTL」等分支。
 * 两层调用要 mock 两层：stringRedisTemplate.opsForValue().get(...)。
 */
@ExtendWith(MockitoExtension.class)
class FrequencyCapServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @InjectMocks
    private FrequencyCapService frequencyCapService;

    @Test
    void notCapped_whenCountBelowLimit() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("2");   // 今天看了 2 次

        assertThat(frequencyCapService.isCapped("u1", "dsp-001", 3)).isFalse();
    }

    @Test
    void capped_whenCountReachesLimit() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("3");   // 已看 3 次，达上限 3

        assertThat(frequencyCapService.isCapped("u1", "dsp-001", 3)).isTrue();
    }

    @Test
    void notCapped_whenNoRecordYet() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);  // 今天还没看过

        assertThat(frequencyCapService.isCapped("u1", "dsp-001", 3)).isFalse();
    }

    @Test
    void notCapped_whenCapDisabled() {
        // cap<=0 表示不限制，直接返回 false，且不应访问 Redis
        assertThat(frequencyCapService.isCapped("u1", "dsp-001", 0)).isFalse();
        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void increment_setsTtlOnFirstHit() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);   // 首次

        frequencyCapService.increment("u1", "dsp-001");

        verify(stringRedisTemplate).expire(anyString(), eq(25L), eq(TimeUnit.HOURS));
    }

    @Test
    void increment_noTtlOnSubsequentHits() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(2L);   // 非首次

        frequencyCapService.increment("u1", "dsp-001");

        verify(stringRedisTemplate, never()).expire(anyString(), anyLong(), eq(TimeUnit.HOURS));
    }
}
