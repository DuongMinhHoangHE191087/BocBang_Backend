package com.bocbang.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BocBangBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BocBangBackendApplication.class, args);
    }
}
