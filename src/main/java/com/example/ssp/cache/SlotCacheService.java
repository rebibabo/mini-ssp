package com.example.ssp.cache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ssp.mapper.AdSlotMapper;
import com.example.ssp.mapper.DspConfigMapper;
import com.example.ssp.mapper.SlotDspRelMapper;
import com.example.ssp.model.entity.AdSlot;
import com.example.ssp.model.entity.DspConfig;
import com.example.ssp.model.entity.SlotDspRel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlotCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final AdSlotMapper adSlotMapper;
    private final DspConfigMapper dspConfigMapper;
    private final SlotDspRelMapper slotDspRelMapper;

    @Value("${ssp.cache.enabled:true}")
    private boolean cacheEnabled;

    @Value("${ssp.cache.slot-ttl-minutes:10}")
    private long slotTtlMinutes;

    @Value("${ssp.cache.dsp-ttl-minutes:10}")
    private long dspTtlMinutes;

    private static final String KEY_SLOT = "ssp:slot:";
    private static final String KEY_SLOT_DSPS = "ssp:slot_dsps:";

    /**
     * 查询广告位：先查 Redis，未命中再查 DB 并写入缓存
     */
    public AdSlot getSlot(String slotId) {
        String key = KEY_SLOT + slotId;

        if (cacheEnabled) {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.debug("[Cache] slot hit: {}", slotId);
                return convertToAdSlot(cached);
            }
            log.debug("[Cache] slot miss: {}", slotId);
        }

        // 查 DB
        AdSlot slot = adSlotMapper.selectOne(
                new LambdaQueryWrapper<AdSlot>()
                        .eq(AdSlot::getSlotId, slotId)
                        .eq(AdSlot::getStatus, 1));

        // 写入缓存
        if (slot != null && cacheEnabled) {
            redisTemplate.opsForValue().set(key, slot, slotTtlMinutes, TimeUnit.MINUTES);
        }

        return slot;
    }

    /**
     * 查询广告位关联的 DSP 列表：先查 Redis，未命中再查 DB 并写入缓存
     */
    public List<DspConfig> getDspsForSlot(String slotId) {
        String key = KEY_SLOT_DSPS + slotId;

        if (cacheEnabled) {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.debug("[Cache] slot_dsps hit: {}", slotId);
                return convertToDspList(cached);
            }
            log.debug("[Cache] slot_dsps miss: {}", slotId);
        }

        // 第一步：查 slot_dsp_rel，找到该广告位关联了哪些 dspId
        // SQL: SELECT * FROM slot_dsp_rel WHERE slot_id = ?
        List<SlotDspRel> rels = slotDspRelMapper.selectList(
                new LambdaQueryWrapper<SlotDspRel>().eq(SlotDspRel::getSlotId, slotId));

        if (rels.isEmpty()) return List.of();

        // 第二步：用 dspId 列表查 dsp_config，拿到完整的 DSP 配置（包含 bidUrl、timeout 等）
        // SQL: SELECT * FROM dsp_config WHERE dsp_id IN (?,?,?) AND status = 1
        List<String> dspIds = rels.stream().map(SlotDspRel::getDspId).toList();
        List<DspConfig> dsps = dspConfigMapper.selectList(
                new LambdaQueryWrapper<DspConfig>()
                        .in(DspConfig::getDspId, dspIds)   // 属于关联的 DSP 集合
                        .eq(DspConfig::getStatus, 1));     // 只要启用状态的

        // 写入缓存，下次直接从 Redis 读，不再查两次 DB
        if (!dsps.isEmpty() && cacheEnabled) {
            redisTemplate.opsForValue().set(key, dsps, dspTtlMinutes, TimeUnit.MINUTES);
        }

        return dsps;
    }

    /**
     * 删除广告位缓存（广告位更新时调用）
     */
    public void evictSlot(String slotId) {
        redisTemplate.delete(KEY_SLOT + slotId);
        redisTemplate.delete(KEY_SLOT_DSPS + slotId);
        log.debug("[Cache] evicted slot: {}", slotId);
    }

    // Redis 取出的是 LinkedHashMap，需要转换回实体类
    @SuppressWarnings("unchecked")
    private AdSlot convertToAdSlot(Object cached) {
        if (cached instanceof AdSlot slot) return slot;
        // Redis 取出的是 LinkedHashMap（Jackson 类型信息丢失），需要转换回 AdSlot
        // convertValue：把 Map 按照 AdSlot 的字段结构重新组装成对象
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());  // 支持 LocalDateTime 转换
        return mapper.convertValue(cached, AdSlot.class);
    }

    @SuppressWarnings("unchecked")
    private List<DspConfig> convertToDspList(Object cached) {
        if (cached instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof DspConfig) {
            return (List<DspConfig>) list;
        }
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // List<DspConfig> 不能直接传 List.class（Java 泛型擦除，运行时不知道装的是什么）
        // constructCollectionType(List.class, DspConfig.class) 相当于描述 List<DspConfig> 这个类型
        return mapper.convertValue(cached,
                mapper.getTypeFactory().constructCollectionType(List.class, DspConfig.class));
    }
}
