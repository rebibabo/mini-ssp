package com.example.ssp.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("slot_dsp_rel")
public class SlotDspRel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String slotId;

    private String dspId;

    private LocalDateTime createdAt;
}
