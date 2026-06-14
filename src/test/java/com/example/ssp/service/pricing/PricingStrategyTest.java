package com.example.ssp.service.pricing;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计价策略单元测试：纯逻辑、无外部依赖，直接 new 出来测各分支。
 * BigDecimal 断言用 isEqualByComparingTo，避免 3.01 vs 3.0100 因精度判不等。
 */
class PricingStrategyTest {

    private final FirstPricePricing firstPrice = new FirstPricePricing();
    private final SecondPricePricing secondPrice = newSecondPrice();

    private SecondPricePricing newSecondPrice() {
        SecondPricePricing s = new SecondPricePricing();
        // @Value 单测不注入，手动设增量
        ReflectionTestUtils.setField(s, "increment", new BigDecimal("0.01"));
        return s;
    }

    @Test
    void firstPrice_paysHighestBid() {
        // 一价：付最高出价本身
        BigDecimal win = firstPrice.computeWinPrice(
                List.of(new BigDecimal("5.00"), new BigDecimal("3.00")), new BigDecimal("0.50"));
        assertThat(win).isEqualByComparingTo("5.00");
    }

    @Test
    void secondPrice_paysSecondPlusIncrement() {
        // 二价：第二高 3.00 + 0.01 = 3.01
        BigDecimal win = secondPrice.computeWinPrice(
                List.of(new BigDecimal("5.00"), new BigDecimal("3.00"), new BigDecimal("1.50")),
                new BigDecimal("0.50"));
        assertThat(win).isEqualByComparingTo("3.01");
    }

    @Test
    void secondPrice_singleBid_paysFloor() {
        // 只有一个有效出价：没有第二高 → 付底价
        BigDecimal win = secondPrice.computeWinPrice(
                List.of(new BigDecimal("4.00")), new BigDecimal("0.50"));
        assertThat(win).isEqualByComparingTo("0.50");
    }

    @Test
    void secondPrice_tieAtTop_cappedAtWinnerBid() {
        // 并列最高 5.00：second+0.01=5.01 会超过赢家出价 → 取 min 封顶到 5.00
        BigDecimal win = secondPrice.computeWinPrice(
                List.of(new BigDecimal("5.00"), new BigDecimal("5.00")), new BigDecimal("0.50"));
        assertThat(win).isEqualByComparingTo("5.00");
    }
}
