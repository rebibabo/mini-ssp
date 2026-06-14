package com.example.ssp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.ssp.mapper.BidLogMapper;
import com.example.ssp.model.entity.BidLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class BidLogService {

    private final BidLogMapper bidLogMapper;

    /**
     * 分页查询竞价日志，支持按 requestId / dspId / status 可选筛选
     *
     * @param requestId 竞价请求 ID（精确匹配），为空则不限
     * @param dspId     DSP 标识（精确匹配），为空则不限
     * @param status    竞价状态 0超时/1有效/2无出价/3异常/4限流，为 null 则不限
     */
    public Page<BidLog> query(String requestId, String dspId, Integer status, int page, int pageSize) {
        LambdaQueryWrapper<BidLog> wrapper = new LambdaQueryWrapper<BidLog>()
                // StringUtils.hasText 为 true 时才拼这个条件，避免空参数也进 WHERE
                .eq(StringUtils.hasText(requestId), BidLog::getRequestId, requestId)
                .eq(StringUtils.hasText(dspId), BidLog::getDspId, dspId)
                .eq(status != null, BidLog::getStatus, status)
                // 最新的排在最前面
                .orderByDesc(BidLog::getCreatedAt);

        return bidLogMapper.selectPage(new Page<>(page, pageSize), wrapper);
    }
}
