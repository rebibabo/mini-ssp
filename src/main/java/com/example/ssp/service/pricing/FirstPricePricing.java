package com.example.ssp.service.pricing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一价拍卖（默认）：中标者支付自己的出价 = 最高出价。
 *
 * {@code matchIfMissing = true}：没配 ssp.bid.auction-type 时也用这个，作为默认策略。
 */
@Component
@ConditionalOnProperty(name = "ssp.bid.auction-type", havingValue = "first", matchIfMissing = true)
public class FirstPricePricing implements PricingStrategy {

    @Override
    public BigDecimal computeWinPrice(List<BigDecimal> sortedBidsDesc, BigDecimal floorPrice) {
        // 第 0 个就是最高出价（赢家）
        return sortedBidsDesc.get(0);
    }
}
