package com.example.ssp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
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

    @Value("${ssp.track.executor.core-size:2}")
    private int trackCoreSize;

    @Value("${ssp.track.executor.max-size:4}")
    private int trackMaxSize;

    @Value("${ssp.track.executor.queue-capacity:10000}")
    private int trackQueueCapacity;

    @Value("${ssp.track.executor.keep-alive-seconds:60}")
    private int trackKeepAliveSeconds;

    @Bean("bidExecutor")
    public Executor bidExecutor() {
        return new ThreadPoolExecutor(
                coreSize,
                maxSize,
                keepAliveSeconds,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Bean("trackExecutor")
    public Executor trackExecutor() {
        return new ThreadPoolExecutor(
                trackCoreSize,
                trackMaxSize,
                trackKeepAliveSeconds,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(trackQueueCapacity),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
