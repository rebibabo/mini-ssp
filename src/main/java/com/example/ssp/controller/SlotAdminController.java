package com.example.ssp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.ssp.model.dto.AdSlotDTO;
import com.example.ssp.model.entity.AdSlot;
import com.example.ssp.model.vo.ApiResponse;
import com.example.ssp.service.SlotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "广告位管理", description = "广告位（AdSlot）的增删改查")
@RestController
@RequestMapping("/api/v1/admin/slots")
@RequiredArgsConstructor
public class SlotAdminController {

    private final SlotService slotService;

    @Operation(summary = "新增广告位")
    @PostMapping
    public ApiResponse<AdSlot> create(@RequestBody @Valid AdSlotDTO dto) {
        return ApiResponse.success(slotService.create(dto));
    }

    @Operation(summary = "按 ID 查询广告位")
    @GetMapping("/{id}")
    public ApiResponse<AdSlot> getById(@PathVariable Long id) {
        return ApiResponse.success(slotService.getById(id));
    }

    @Operation(summary = "分页查询广告位列表")
    @GetMapping
    public ApiResponse<Page<AdSlot>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(slotService.list(page, pageSize));
    }

    @Operation(summary = "更新广告位")
    @PutMapping("/{id}")
    public ApiResponse<AdSlot> update(@PathVariable Long id, @RequestBody @Valid AdSlotDTO dto) {
        return ApiResponse.success(slotService.update(id, dto));
    }

    @Operation(summary = "删除广告位")
    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(@PathVariable Long id) {
        slotService.delete(id);
        return ApiResponse.success();
    }
}
