package com.wardrobe;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.wardrobe.mapper")
public class AiWardrobeApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiWardrobeApplication.class, args);
    }
}
