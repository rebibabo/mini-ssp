package com.example.ssp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.ssp.model.dto.DspConfigDTO;
import com.example.ssp.model.entity.DspConfig;
import com.example.ssp.model.vo.ApiResponse;
import com.example.ssp.service.DspService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "DSP 管理", description = "DSP（需求方平台）配置的增删改查")
@RestController
@RequestMapping("/api/v1/admin/dsps")
@RequiredArgsConstructor
public class DspAdminController {

    private final DspService dspService;

    @Operation(summary = "新增 DSP")
    @PostMapping
    public ApiResponse<DspConfig> create(@RequestBody @Valid DspConfigDTO dto) {
        return ApiResponse.success(dspService.create(dto));
    }

    @Operation(summary = "按 ID 查询 DSP")
    @GetMapping("/{id}")
    public ApiResponse<DspConfig> getById(@PathVariable Long id) {
        return ApiResponse.success(dspService.getById(id));
    }

    @Operation(summary = "分页查询 DSP 列表")
    @GetMapping
    public ApiResponse<Page<DspConfig>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(dspService.list(page, pageSize));
    }

    @Operation(summary = "更新 DSP")
    @PutMapping("/{id}")
    public ApiResponse<DspConfig> update(@PathVariable Long id, @RequestBody @Valid DspConfigDTO dto) {
        return ApiResponse.success(dspService.update(id, dto));
    }

    @Operation(summary = "删除 DSP")
    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(@PathVariable Long id) {
        dspService.delete(id);
        return ApiResponse.success();
    }
}
