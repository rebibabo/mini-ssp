package com.example.ssp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.ssp.model.dto.BidRequest;
import com.example.ssp.model.dto.DeviceDTO;
import com.example.ssp.model.dto.BidResponse;
import com.example.ssp.model.vo.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TrackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testImpressionAndClick() throws Exception {
        // 第一步：先发竞价请求，把结果存进 Redis
        String requestId = "req-track-" + System.currentTimeMillis();
        BidRequest bidRequest = buildBidRequest(requestId);

        MvcResult bidResult = mockMvc.perform(post("/api/v1/bid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bidRequest)))
                .andExpect(status().isOk())
                .andReturn();

        // 解析竞价响应，确认是否有广告填充
        String bidJson = bidResult.getResponse().getContentAsString();
        ApiResponse response = objectMapper.readValue(bidJson, ApiResponse.class);

        if (response.getCode() != 0) {
            // no fill，跳过埋点测试（Mock DSP 有随机不出价的概率）
            System.out.println("No fill, skip track test");
            return;
        }

        // 第二步：测曝光接口，期望返回 204
        mockMvc.perform(get("/api/v1/track/impression")
                        .param("rid", requestId))
                .andExpect(status().isNoContent());

        // 第三步：测点击接口，期望返回 302 重定向
        mockMvc.perform(get("/api/v1/track/click")
                        .param("rid", requestId))
                .andExpect(status().isFound());  // 302 = Found
    }

    @Test
    void testImpressionWithInvalidRid() throws Exception {
        // requestId 不存在（Redis 里没有），曝光接口应该返回 204（静默忽略）
        mockMvc.perform(get("/api/v1/track/impression")
                        .param("rid", "req-not-exist"))
                .andExpect(status().isNoContent());
    }

    @Test
    void testClickWithInvalidRid() throws Exception {
        // requestId 不存在，点击接口应该返回 302 重定向到默认页 "/"
        mockMvc.perform(get("/api/v1/track/click")
                        .param("rid", "req-not-exist"))
                .andExpect(status().isFound());
    }

    // ---------- 工具方法 ----------

    private BidRequest buildBidRequest(String requestId) {
        BidRequest request = new BidRequest();
        request.setRequestId(requestId);
        request.setAdSlotId("slot-test-001");

        DeviceDTO device = new DeviceDTO();
        device.setOs("iOS");
        device.setOsVersion("17.0");
        device.setModel("iPhone 15");
        device.setIp("1.2.3.4");
        request.setDevice(device);

        return request;
    }
}
