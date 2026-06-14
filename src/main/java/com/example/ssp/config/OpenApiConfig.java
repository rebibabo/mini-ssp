package com.example.ssp.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger/OpenAPI 全局配置。
 *
 * springdoc 生成文档时会优先使用容器里的 OpenAPI Bean，
 * 没有则用默认标题 "OpenAPI definition"。这里提供项目自己的标题/版本/描述，
 * 显示在 swagger-ui 页面顶部。只是文档元数据，不影响任何运行逻辑。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI sspOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Mini-SSP API")
                .version("v1")
                .description("简化版 SSP（供给方平台）竞价接口文档：竞价、埋点追踪、广告位/DSP 管理、竞价日志查询。"));
    }
}
