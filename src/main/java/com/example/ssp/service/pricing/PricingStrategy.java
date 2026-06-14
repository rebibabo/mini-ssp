package com.example.ssp.service.pricing;

import java.math.BigDecimal;
import java.util.List;

/**
 * 拍卖计价策略：决定中标者实际支付的价格（win_price）。
 *
 * 同一件事（算中标价）有多种算法，按配置 {@code ssp.bid.auction-type} 切换：
 * <ul>
 *   <li>{@code first}（默认）→ {@link FirstPricePricing}：付自己的出价</li>
 *   <li>{@code second} → {@link SecondPricePricing}：付第二高价 + 增量</li>
 * </ul>
 * 和 DspCaller 一样是策略模式：BidService 只依赖本接口，运行时按配置注入具体实现。
 */
public interface PricingStrategy {

    /**
     * 计算中标价。
     *
     * @param sortedBidsDesc 所有有效出价（已按价格降序），第 0 个即赢家的出价；至少 1 个
     * @param floorPrice     广告位底价
     * @return 中标者实际支付的价格
     */
    BigDecimal computeWinPrice(List<BigDecimal> sortedBidsDesc, BigDecimal floorPrice);
}
