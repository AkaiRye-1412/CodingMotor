package com.motorsizer.app;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.motorsizer.app.mapper")
public class MotorsizerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MotorsizerApplication.class, args);
    }
}
