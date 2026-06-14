package com.example.ssp.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("event_log")
public class EventLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String requestId;

    // 1=曝光 2=点击
    private Integer eventType;

    private String slotId;

    private String dspId;

    private BigDecimal winPrice;

    private String ip;

    private String ua;

    private LocalDateTime createdAt;
}
