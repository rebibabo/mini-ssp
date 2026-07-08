package com.example.ssp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.ssp.cache.SlotCacheService;
import com.example.ssp.exception.BizException;
import com.example.ssp.mapper.AdSlotMapper;
import com.example.ssp.model.dto.AdSlotDTO;
import com.example.ssp.model.entity.AdSlot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SlotService {

    private final AdSlotMapper adSlotMapper;
    private final SlotCacheService slotCacheService;

    public AdSlot create(AdSlotDTO dto) {
        // 检查 slotId 是否已存在
        Long count = adSlotMapper.selectCount(
                new LambdaQueryWrapper<AdSlot>().eq(AdSlot::getSlotId, dto.getSlotId())
        );
        if (count > 0) {
            throw new BizException(400, "广告位 ID 已存在: " + dto.getSlotId());
        }

        // DTO 转 Entity
        AdSlot slot = new AdSlot();
        slot.setSlotId(dto.getSlotId());
        slot.setName(dto.getName());
        slot.setWidth(dto.getWidth());
        slot.setHeight(dto.getHeight());
        slot.setType(dto.getType());
        slot.setFloorPrice(dto.getFloorPrice());
        slot.setStatus(dto.getStatus());

        adSlotMapper.insert(slot);
        slotCacheService.evictSlot(slot.getSlotId());
        return slot;
    }

    public AdSlot getById(Long id) {
        AdSlot slot = adSlotMapper.selectById(id);
        if (slot == null) {
            throw new BizException(404, "广告位不存在");
        }
        return slot;
    }

    public Page<AdSlot> list(int page, int pageSize) {
        return adSlotMapper.selectPage(new Page<>(page, pageSize), null);
    }

    public AdSlot update(Long id, AdSlotDTO dto) {
        AdSlot slot = getById(id);
        slot.setName(dto.getName());
        slot.setWidth(dto.getWidth());
        slot.setHeight(dto.getHeight());
        slot.setType(dto.getType());
        slot.setFloorPrice(dto.getFloorPrice());
        slot.setStatus(dto.getStatus());

        adSlotMapper.updateById(slot);
        slotCacheService.evictSlot(slot.getSlotId());
        return slot;
    }

    public void delete(Long id) {
        AdSlot slot = getById(id); // 检查是否存在
        adSlotMapper.deleteById(id);
        slotCacheService.evictSlot(slot.getSlotId());
    }
}
