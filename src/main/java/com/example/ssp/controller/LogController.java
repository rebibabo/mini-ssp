package com.example.ssp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.ssp.model.entity.BidLog;
import com.example.ssp.model.vo.ApiResponse;
import com.example.ssp.service.BidLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "竞价日志", description = "竞价流水（bid_log）只读查询，排查每次竞价各 DSP 的表现")
@RestController
@RequestMapping("/api/v1/admin/logs")
@RequiredArgsConstructor
public class LogController {

    private final BidLogService bidLogService;

    /**
     * 查询竞价日志（只读），支持按 requestId / dspId / status 可选筛选 + 分页
     */
    @Operation(summary = "查询竞价日志", description = "按 requestId / dspId / status 可选筛选 + 分页")
    @GetMapping
    public ApiResponse<Page<BidLog>> query(
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String dspId,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(
                bidLogService.query(requestId, dspId, status, page, pageSize));
    }
}
