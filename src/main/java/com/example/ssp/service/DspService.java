package com.example.ssp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.ssp.cache.SlotCacheService;
import com.example.ssp.exception.BizException;
import com.example.ssp.mapper.DspConfigMapper;
import com.example.ssp.model.dto.DspConfigDTO;
import com.example.ssp.model.entity.DspConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DspService {

    private final DspConfigMapper dspConfigMapper;
    private final SlotCacheService slotCacheService;

    public DspConfig create(DspConfigDTO dto) {
        // 检查 dspId 是否已存在
        Long count = dspConfigMapper.selectCount(
                new LambdaQueryWrapper<DspConfig>().eq(DspConfig::getDspId, dto.getDspId())
        );
        if (count > 0) {
            throw new BizException(400, "DSP ID 已存在: " + dto.getDspId());
        }

        // DTO 转 Entity
        DspConfig dsp = new DspConfig();
        dsp.setDspId(dto.getDspId());
        dsp.setName(dto.getName());
        dsp.setBidUrl(dto.getBidUrl());
        dsp.setTimeoutMs(dto.getTimeoutMs());
        dsp.setQpsLimit(dto.getQpsLimit());
        dsp.setStatus(dto.getStatus());

        dspConfigMapper.insert(dsp); // 向数据库插入dsp
        slotCacheService.refreshLocalCache();
        return dsp;
    }

    public DspConfig getById(Long id) {
        DspConfig dsp = dspConfigMapper.selectById(id);
        if (dsp == null) {
            throw new BizException(404, "DSP 不存在");
        }
        return dsp;
    }

    public Page<DspConfig> list(int page, int pageSize) {
        return dspConfigMapper.selectPage(new Page<>(page, pageSize), null);
    }

    public DspConfig update(Long id, DspConfigDTO dto) {
        DspConfig dsp = getById(id);
        dsp.setName(dto.getName());
        dsp.setBidUrl(dto.getBidUrl());
        dsp.setTimeoutMs(dto.getTimeoutMs());
        dsp.setQpsLimit(dto.getQpsLimit());
        dsp.setStatus(dto.getStatus());

        dspConfigMapper.updateById(dsp);
        slotCacheService.refreshLocalCache();
        return dsp;
    }

    public void delete(Long id) {
        getById(id); // 检查是否存在
        dspConfigMapper.deleteById(id);
        slotCacheService.refreshLocalCache();
    }
}
