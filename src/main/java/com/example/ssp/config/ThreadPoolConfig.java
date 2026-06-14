package com.example.ssp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class ThreadPoolConfig {

    @Value("${ssp.bid.thread-pool.core-size:8}")
    private int coreSize;

    @Value("${ssp.bid.thread-pool.max-size:16}")
    private int maxSize;

    @Value("${ssp.bid.thread-pool.queue-capacity:200}")
    private int queueCapacity;

    @Value("${ssp.bid.thread-pool.keep-alive-seconds:60}")
    private int keepAliveSeconds;

    @Bean("bidExecutor")
    public Executor bidExecutor() {
        return new ThreadPoolExecutor(
                coreSize,
                maxSize,
                keepAliveSeconds,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
