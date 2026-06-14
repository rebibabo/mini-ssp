package com.example.ssp.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("bid_log")
public class BidLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String requestId;

    private String slotId;

    private String dspId;

    private BigDecimal bidPrice;

    private Integer responseTimeMs;

    // 0=超时 1=有效出价 2=无出价 3=异常
    private Integer status;

    // 0=否 1=是
    private Integer win;

    // 中标成交价（由计价策略算出，一价=出价/二价=第二高+增量）；仅中标记录有值，其余为 null
    private BigDecimal winPrice;

    private LocalDateTime createdAt;
}
