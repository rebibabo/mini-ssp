package com.example.mockdsp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 独立 Mock DSP 服务启动类。
 *
 * 用不同 profile 启动 3 个实例模拟 3 个 DSP：
 * <pre>
 *   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dsp-a   # 8081
 *   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dsp-b   # 8082
 *   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dsp-c   # 8083
 * </pre>
 */
@SpringBootApplication
@EnableConfigurationProperties(DspBehaviorProperties.class)
public class MockDspApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockDspApplication.class, args);
    }
}
