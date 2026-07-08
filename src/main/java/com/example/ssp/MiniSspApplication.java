package com.example.ssp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.example.ssp.mapper")
@EnableScheduling
public class MiniSspApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniSspApplication.class, args);
    }

}
