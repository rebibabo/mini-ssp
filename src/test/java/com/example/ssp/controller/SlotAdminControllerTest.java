package com.example.ssp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.ssp.model.dto.AdSlotDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SlotAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testCreate() throws Exception {
        // 准备请求数据
        AdSlotDTO dto = new AdSlotDTO();
        String slotId = "slot-test-" + System.currentTimeMillis();
        dto.setSlotId(slotId);
        dto.setName("测试横幅广告位");
        dto.setWidth(320);
        dto.setHeight(50);
        dto.setType(1);
        dto.setFloorPrice(new BigDecimal("0.5000"));
        dto.setStatus(1);

        // 发送 POST 请求，验证响应
        mockMvc.perform(post("/api/v1/admin/slots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.slotId").value(slotId))
                .andExpect(jsonPath("$.data.name").value("测试横幅广告位"));
    }

    @Test
    void testGetById() throws Exception {
        // 查询 id=1 的广告位
        mockMvc.perform(get("/api/v1/admin/slots/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testList() throws Exception {
        // 查询列表，默认第 1 页，每页 20 条
        mockMvc.perform(get("/api/v1/admin/slots")
                        .param("page", "1")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testCreateWithInvalidParam() throws Exception {
        // 缺少必填字段，应该返回 400
        AdSlotDTO dto = new AdSlotDTO();
        dto.setName("缺少slotId");

        mockMvc.perform(post("/api/v1/admin/slots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
