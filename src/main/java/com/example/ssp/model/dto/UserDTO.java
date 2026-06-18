package com.example.ssp.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "用户信息，DSP 用于精准投放")
@Data
public class UserDTO {

    // OpenRTB 2.5: user.id —— 交易所/SSP 侧的用户 ID
    @JsonProperty("id")
    @Schema(description = "用户唯一标识（OpenRTB user.id）", example = "u1")
    private String userId;

    // 注：OpenRTB 用出生年 user.yob，本项目暂保留 age（语义不同，留待 A+ 阶段处理）
    @Schema(description = "用户年龄", example = "25")
    private Integer age;

    @Schema(description = "性别：M=男 F=女", example = "M")
    private String gender;

    // OpenRTB 2.5: user.keywords —— 兴趣关键词（协议里是逗号分隔字符串，本项目简化为数组）
    @JsonProperty("keywords")
    @Schema(description = "兴趣标签列表（OpenRTB user.keywords），DSP 用于精准投放", example = "[\"gaming\",\"tech\"]")
    private List<String> interests;
}
