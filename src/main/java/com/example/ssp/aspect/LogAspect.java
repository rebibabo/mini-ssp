package com.example.ssp.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;

/**
 * 记录所有 Controller 接口的请求参数、耗时和返回结果
 */
@Slf4j
@Aspect
@Component
public class LogAspect {

    @Pointcut("execution(* com.example.ssp.controller..*.*(..))")
    public void controllerMethods() {
    }

    @Around("controllerMethods()")
    public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {
        String signature = joinPoint.getSignature().toShortString();
        String uri = getRequestUri();
        long start = System.currentTimeMillis();

        log.info("[Req] {} {} args={}", uri, signature, Arrays.toString(joinPoint.getArgs()));

        try {
            Object result = joinPoint.proceed();
            long elapsedMs = System.currentTimeMillis() - start;
            log.info("[Resp] {} {} cost={}ms result={}", uri, signature, elapsedMs, result);
            return result;
        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - start;
            log.error("[Resp] {} {} cost={}ms error={}", uri, signature, elapsedMs, e.getMessage());
            throw e;
        }
    }

    // 从当前线程的请求上下文中取出 URI，可能为 null（如非 HTTP 请求触发的调用）
    private String getRequestUri() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getRequest().getRequestURI() : "-";
    }
}
