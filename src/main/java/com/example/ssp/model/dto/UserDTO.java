package com.example.ssp.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "用户信息，DSP 用于精准投放")
@Data
public class UserDTO {

    @Schema(description = "用户唯一标识", example = "u1")
    private String userId;

    @Schema(description = "用户年龄", example = "25")
    private Integer age;

    @Schema(description = "性别：M=男 F=女", example = "M")
    private String gender;

    @Schema(description = "兴趣标签列表，DSP 用于精准投放", example = "[\"gaming\",\"tech\"]")
    private List<String> interests;
}
