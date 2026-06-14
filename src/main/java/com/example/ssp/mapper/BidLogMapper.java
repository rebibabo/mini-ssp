package com.example.ssp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ssp.model.entity.BidLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BidLogMapper extends BaseMapper<BidLog> {

    /**
     * 批量插入：一条 INSERT 拼多行 VALUES，减少 DB 往返。
     * Kafka 消费者批量收到一批 BidLog 后调用，比逐条 insert 高效。
     * <script> 里用 <foreach> 把 list 展开成 (..),(..),(..)。
     */
    @Insert("<script>" +
            "INSERT INTO bid_log " +
            "(request_id, slot_id, dsp_id, bid_price, response_time_ms, status, win, win_price, created_at) " +
            "VALUES " +
            "<foreach collection='list' item='it' separator=','>" +
            "(#{it.requestId}, #{it.slotId}, #{it.dspId}, #{it.bidPrice}, #{it.responseTimeMs}, " +
            " #{it.status}, #{it.win}, #{it.winPrice}, #{it.createdAt})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<BidLog> logs);
}
