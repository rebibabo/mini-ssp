package com.example.ssp.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ad_slot")
public class AdSlot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String slotId;

    private String name;

    private Integer width;

    private Integer height;

    // 1=横幅 2=插屏 3=开屏 4=信息流
    private Integer type;

    private BigDecimal floorPrice;

    // 0=禁用 1=启用
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
