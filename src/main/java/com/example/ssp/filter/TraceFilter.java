package com.example.ssp.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求级别的 traceId 注入过滤器。
 *
 * 执行时机：比 Controller 更早，每个 HTTP 请求只执行一次（OncePerRequestFilter 保证）。
 *
 * 做了三件事：
 *   1. 生成 traceId（优先取客户端传的 X-Request-Id header，没有就随机生成 UUID）
 *   2. 把 traceId 存入 MDC（ThreadLocal<Map>），同一线程后续所有 log.xxx() 调用
 *      都会自动把 MDC 里的值插入日志，格式由 logback-spring.xml 的 %X{traceId} 控制
 *   3. 请求结束后在 finally 里清除 MDC，防止线程池复用线程时把上一个请求的 traceId 带到下一个请求
 *
 * 注意：对于竞价接口，BidController 解析到业务 requestId 后会用它覆盖这里生成的 UUID，
 * 保持 traceId 和业务 id 一致，便于通过 requestId 串联日志。
 */
@Component
public class TraceFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String TRACE_HEADER  = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 优先用客户端传来的 id，方便前端/调用方自己生成 id 做端到端追踪
        String traceId = request.getHeader(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            // 没有则随机生成，保证每个请求都有 traceId（admin/track 等接口没有业务 id）
            traceId = UUID.randomUUID().toString();
        }

        // 放入 MDC：之后同一线程打的每一行日志都会自动带上 traceId，无需手动传参
        MDC.put(TRACE_ID_KEY, traceId);
        // 回写到响应头，方便客户端拿到 traceId 反查日志
        response.setHeader(TRACE_HEADER, traceId);

        try {
            // 放行请求，继续走 Controller → Service 链路
            filterChain.doFilter(request, response);
        } finally {
            // 请求结束必须清除！
            // 线程池会复用线程，不清的话下一个请求会继承上一个请求的 traceId，日志全乱
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
