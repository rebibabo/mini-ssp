package com.example.ssp.service.pricing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 二价拍卖（GSP）：中标者仍是最高出价者，但实际支付 = 第二高价 + 增量。
 *
 * 边界：
 * <ul>
 *   <li>≥2 个有效出价：winPrice = 第二高 + 增量，且不超过赢家自己的出价（取 min）</li>
 *   <li>只有 1 个有效出价：没有第二高 → 支付底价（行业惯例）</li>
 * </ul>
 * 第二高本身已 ≥ 底价（竞价前按底价过滤过），故结果不会低于底价。
 */
@Component
@ConditionalOnProperty(name = "ssp.bid.auction-type", havingValue = "second")
public class SecondPricePricing implements PricingStrategy {

    // 二价增量，默认 0.01；可在 application.yml 配 ssp.bid.price-increment
    @Value("${ssp.bid.price-increment:0.01}")
    private BigDecimal increment;

    @Override
    public BigDecimal computeWinPrice(List<BigDecimal> sortedBidsDesc, BigDecimal floorPrice) {
        // 只有一个有效出价：没有第二高，付底价
        if (sortedBidsDesc.size() < 2) {
            return floorPrice;
        }
        BigDecimal winnerBid = sortedBidsDesc.get(0);
        BigDecimal secondBid = sortedBidsDesc.get(1);
        BigDecimal price = secondBid.add(increment);
        // 不超过赢家自己的出价（并列最高时 second+increment 可能略高于 winnerBid）
        return price.min(winnerBid);
    }
}
