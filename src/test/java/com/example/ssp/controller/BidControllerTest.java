package com.example.ssp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.ssp.model.dto.BidRequest;
import com.example.ssp.model.dto.DeviceDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BidControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testBidSuccess() throws Exception {
        // 准备请求：有效广告位 + 3 个 DSP
        BidRequest request = buildRequest("slot-test-001");

        mockMvc.perform(post("/api/v1/bid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                // code=0 表示有广告填充，code=1 表示 no fill，两个都是正常结果
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testBidNoFill_SlotNotFound() throws Exception {
        // 广告位不存在，应该返回 404
        BidRequest request = buildRequest("slot-not-exist");

        mockMvc.perform(post("/api/v1/bid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void testBidResponseContent() throws Exception {
        // 验证竞价成功时，返回的广告内容字段完整
        BidRequest request = buildRequest("slot-test-001");

        mockMvc.perform(post("/api/v1/bid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.requestId").value(request.getRequestId()))
                .andExpect(jsonPath("$.data.adSlotId").value("slot-test-001"))
                .andExpect(jsonPath("$.data.winDsp").isNotEmpty())
                .andExpect(jsonPath("$.data.winPrice").isNumber())
                .andExpect(jsonPath("$.data.adContent.title").isNotEmpty())
                .andExpect(jsonPath("$.data.adContent.impressionTrackUrl").value(
                        "/api/v1/track/impression?rid=" + request.getRequestId()))
                .andExpect(jsonPath("$.data.adContent.clickTrackUrl").value(
                        "/api/v1/track/click?rid=" + request.getRequestId()));
    }

    @Test
    void testBidNoFill_NoDsp() throws Exception {
        // 广告位存在但没有关联任何 DSP，必然 no fill
        BidRequest request = buildRequest("slot-no-dsp");

        mockMvc.perform(post("/api/v1/bid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));  // 1 = no fill
    }

    @Test
    void testBidInvalidParam() throws Exception {
        // 缺少必填字段 requestId，应该返回 400
        BidRequest request = new BidRequest();
        request.setAdSlotId("slot-test-001");
        // requestId 故意不设置

        DeviceDTO device = new DeviceDTO();
        device.setOs("iOS");
        request.setDevice(device);

        mockMvc.perform(post("/api/v1/bid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ---------- 工具方法 ----------

    private BidRequest buildRequest(String slotId) {
        BidRequest request = new BidRequest();
        request.setRequestId("req-" + System.currentTimeMillis());
        request.setAdSlotId(slotId);

        DeviceDTO device = new DeviceDTO();
        device.setOs("iOS");
        device.setOsVersion("17.0");
        device.setModel("iPhone 15");
        device.setIp("1.2.3.4");
        request.setDevice(device);

        return request;
    }
}
