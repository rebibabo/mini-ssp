package com.example.ssp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ssp.model.entity.EventLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EventLogMapper extends BaseMapper<EventLog> {
}
