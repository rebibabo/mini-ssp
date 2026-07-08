package com.example.ssp.cache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ssp.mapper.AdSlotMapper;
import com.example.ssp.mapper.DspConfigMapper;
import com.example.ssp.mapper.SlotDspRelMapper;
import com.example.ssp.model.entity.AdSlot;
import com.example.ssp.model.entity.DspConfig;
import com.example.ssp.model.entity.SlotDspRel;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlotCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final AdSlotMapper adSlotMapper;
    private final DspConfigMapper dspConfigMapper;
    private final SlotDspRelMapper slotDspRelMapper;
    private final ObjectMapper objectMapper;

    @Value("${ssp.cache.enabled:true}")
    private boolean cacheEnabled;

    @Value("${ssp.cache.slot-ttl-minutes:10}")
    private long slotTtlMinutes;

    @Value("${ssp.cache.dsp-ttl-minutes:10}")
    private long dspTtlMinutes;

    @Value("${ssp.perf.static-config-enabled:false}")
    private boolean staticConfigEnabled;

    @Value("${ssp.trace.timeline-enabled:false}")
    private boolean timelineTraceEnabled;

    @Value("${ssp.cache.local-enabled:false}")
    private boolean localCacheEnabled;

    private static final String KEY_SLOT = "ssp:slot:";
    private static final String KEY_SLOT_DSPS = "ssp:slot_dsps:";

    private volatile Map<String, AdSlot> localSlots = Map.of();
    private volatile Map<String, List<DspConfig>> localSlotDsps = Map.of();

    /**
     * 查询广告位：先查 Redis，未命中再查 DB 并写入缓存
     */
    public AdSlot getSlot(String slotId) {
        if (staticConfigEnabled) {
            return buildPerfSlot(slotId);
        }

        if (localCacheEnabled) {
            long localStartNs = System.nanoTime();
            AdSlot slot = localSlots.get(slotId);
            trace(slotId, "slot local cache GET", localStartNs);
            return slot;
        }

        String key = KEY_SLOT + slotId;

        if (cacheEnabled) {
            long redisGetStartNs = System.nanoTime();
            Object cached = redisTemplate.opsForValue().get(key);
            trace(slotId, "slot redis GET", redisGetStartNs);
            if (cached != null) {
                log.debug("[Cache] slot hit: {}", slotId);
                long convertStartNs = System.nanoTime();
                AdSlot slot = convertToAdSlot(cached);
                trace(slotId, "slot cached object convert", convertStartNs);
                return slot;
            }
            log.debug("[Cache] slot miss: {}", slotId);
        }

        // 查 DB
        long dbStartNs = System.nanoTime();
        AdSlot slot = adSlotMapper.selectOne(
                new LambdaQueryWrapper<AdSlot>()
                        .eq(AdSlot::getSlotId, slotId)
                        .eq(AdSlot::getStatus, 1));
        trace(slotId, "slot DB select", dbStartNs);

        // 写入缓存
        if (slot != null && cacheEnabled) {
            long redisSetStartNs = System.nanoTime();
            redisTemplate.opsForValue().set(key, slot, slotTtlMinutes, TimeUnit.MINUTES);
            trace(slotId, "slot redis SET", redisSetStartNs);
        }

        return slot;
    }

    /**
     * 查询广告位关联的 DSP 列表：先查 Redis，未命中再查 DB 并写入缓存
     */
    public List<DspConfig> getDspsForSlot(String slotId) {
        if (staticConfigEnabled) {
            return buildPerfDsps();
        }

        if (localCacheEnabled) {
            long localStartNs = System.nanoTime();
            List<DspConfig> dsps = localSlotDsps.get(slotId);
            trace(slotId, "slot_dsps local cache GET", localStartNs);
            return dsps == null ? List.of() : dsps;
        }

        String key = KEY_SLOT_DSPS + slotId;

        if (cacheEnabled) {
            long redisGetStartNs = System.nanoTime();
            Object cached = redisTemplate.opsForValue().get(key);
            trace(slotId, "slot_dsps redis GET", redisGetStartNs);
            if (cached != null) {
                log.debug("[Cache] slot_dsps hit: {}", slotId);
                long convertStartNs = System.nanoTime();
                List<DspConfig> dsps = convertToDspList(cached);
                trace(slotId, "slot_dsps cached object convert", convertStartNs);
                return dsps;
            }
            log.debug("[Cache] slot_dsps miss: {}", slotId);
        }

        // 第一步：查 slot_dsp_rel，找到该广告位关联了哪些 dspId
        // SQL: SELECT * FROM slot_dsp_rel WHERE slot_id = ?
        long relSelectStartNs = System.nanoTime();
        List<SlotDspRel> rels = slotDspRelMapper.selectList(
                new LambdaQueryWrapper<SlotDspRel>().eq(SlotDspRel::getSlotId, slotId));
        trace(slotId, "slot_dsps rel DB select", relSelectStartNs);

        if (rels.isEmpty()) return List.of();

        // 第二步：用 dspId 列表查 dsp_config，拿到完整的 DSP 配置（包含 bidUrl、timeout 等）
        // SQL: SELECT * FROM dsp_config WHERE dsp_id IN (?,?,?) AND status = 1
        List<String> dspIds = rels.stream().map(SlotDspRel::getDspId).toList();
        long dspSelectStartNs = System.nanoTime();
        List<DspConfig> dsps = dspConfigMapper.selectList(
                new LambdaQueryWrapper<DspConfig>()
                        .in(DspConfig::getDspId, dspIds)   // 属于关联的 DSP 集合
                        .eq(DspConfig::getStatus, 1));     // 只要启用状态的
        trace(slotId, "slot_dsps dsp DB select", dspSelectStartNs);

        // 写入缓存，下次直接从 Redis 读，不再查两次 DB
        if (!dsps.isEmpty() && cacheEnabled) {
            long redisSetStartNs = System.nanoTime();
            redisTemplate.opsForValue().set(key, dsps, dspTtlMinutes, TimeUnit.MINUTES);
            trace(slotId, "slot_dsps redis SET", redisSetStartNs);
        }

        return dsps;
    }

    /**
     * 删除广告位缓存（广告位更新时调用）
     */
    public void evictSlot(String slotId) {
        try {
            redisTemplate.delete(KEY_SLOT + slotId);
            redisTemplate.delete(KEY_SLOT_DSPS + slotId);
        } catch (Exception e) {
            log.error("[Cache] evict Redis slot failed, slotId={}: {}", slotId, e.getMessage(), e);
        }
        refreshLocalCache();
        log.debug("[Cache] evicted slot: {}", slotId);
    }

    /**
     * 本地配置缓存：用整份不可变快照替换，竞价线程读的时候无需加锁。
     */
    public void refreshLocalCache() {
        if (!localCacheEnabled || staticConfigEnabled) {
            return;
        }

        long startNs = System.nanoTime();
        try {
            List<AdSlot> slots = adSlotMapper.selectList(
                    new LambdaQueryWrapper<AdSlot>().eq(AdSlot::getStatus, 1));
            List<DspConfig> dsps = dspConfigMapper.selectList(
                    new LambdaQueryWrapper<DspConfig>().eq(DspConfig::getStatus, 1));
            List<SlotDspRel> rels = slotDspRelMapper.selectList(null);

            Map<String, AdSlot> nextSlots = slots.stream()
                    .collect(Collectors.toUnmodifiableMap(AdSlot::getSlotId, slot -> slot));
            Map<String, DspConfig> dspById = dsps.stream()
                    .collect(Collectors.toUnmodifiableMap(DspConfig::getDspId, dsp -> dsp));
            Set<String> enabledSlotIds = nextSlots.keySet();

            Map<String, List<DspConfig>> nextSlotDsps = new ConcurrentHashMap<>();
            for (SlotDspRel rel : rels) {
                if (!enabledSlotIds.contains(rel.getSlotId())) {
                    continue;
                }
                DspConfig dsp = dspById.get(rel.getDspId());
                if (dsp == null) {
                    continue;
                }
                nextSlotDsps.computeIfAbsent(rel.getSlotId(), ignored -> new ArrayList<>()).add(dsp);
            }

            Map<String, List<DspConfig>> immutableSlotDsps = nextSlotDsps.entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(
                            Map.Entry::getKey,
                            entry -> Collections.unmodifiableList(entry.getValue())));

            localSlots = nextSlots;
            localSlotDsps = immutableSlotDsps;
            log.info("[LocalCache] refreshed slots={}, slotDspMappings={}, stepMs={}",
                    localSlots.size(), localSlotDsps.size(), nanosToMillis(System.nanoTime() - startNs));
        } catch (Exception e) {
            log.error("[LocalCache] refresh failed, keep old snapshot: {}", e.getMessage(), e);
        }
    }

    @PostConstruct
    public void initLocalCache() {
        refreshLocalCache();
    }

    @Scheduled(fixedDelayString = "${ssp.cache.local-refresh-ms:5000}")
    public void scheduledRefreshLocalCache() {
        refreshLocalCache();
    }

    // Redis 取出的是 LinkedHashMap，需要转换回实体类
    @SuppressWarnings("unchecked")
    private AdSlot convertToAdSlot(Object cached) {
        if (cached instanceof AdSlot slot) return slot;
        // Redis 取出的是 LinkedHashMap（Jackson 类型信息丢失），需要转换回 AdSlot
        // convertValue：把 Map 按照 AdSlot 的字段结构重新组装成对象
        return objectMapper.convertValue(cached, AdSlot.class);
    }

    @SuppressWarnings("unchecked")
    private List<DspConfig> convertToDspList(Object cached) {
        if (cached instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof DspConfig) {
            return (List<DspConfig>) list;
        }
        // List<DspConfig> 不能直接传 List.class（Java 泛型擦除，运行时不知道装的是什么）
        // constructCollectionType(List.class, DspConfig.class) 相当于描述 List<DspConfig> 这个类型
        return objectMapper.convertValue(cached,
                objectMapper.getTypeFactory().constructCollectionType(List.class, DspConfig.class));
    }

    private AdSlot buildPerfSlot(String slotId) {
        AdSlot slot = new AdSlot();
        slot.setSlotId(slotId);
        slot.setName("perf-slot");
        slot.setWidth(320);
        slot.setHeight(50);
        slot.setType(1);
        slot.setFloorPrice(new BigDecimal("0.10"));
        slot.setStatus(1);
        slot.setCreatedAt(LocalDateTime.now());
        slot.setUpdatedAt(LocalDateTime.now());
        return slot;
    }

    private List<DspConfig> buildPerfDsps() {
        return List.of(
                buildPerfDsp("dsp-001"),
                buildPerfDsp("dsp-002"),
                buildPerfDsp("dsp-003")
        );
    }

    private DspConfig buildPerfDsp(String dspId) {
        DspConfig dsp = new DspConfig();
        dsp.setDspId(dspId);
        dsp.setName(dspId);
        dsp.setBidUrl("mock://" + dspId);
        dsp.setTimeoutMs(150);
        dsp.setQpsLimit(0);
        dsp.setStatus(1);
        dsp.setCreatedAt(LocalDateTime.now());
        dsp.setUpdatedAt(LocalDateTime.now());
        return dsp;
    }

    private void trace(String slotId, String stage, long startNs) {
        if (timelineTraceEnabled) {
            log.info("[Timeline] slotId={} stage=\"{}\" stepMs={}",
                    slotId, stage, nanosToMillis(System.nanoTime() - startNs));
        }
    }

    private double nanosToMillis(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }
}
