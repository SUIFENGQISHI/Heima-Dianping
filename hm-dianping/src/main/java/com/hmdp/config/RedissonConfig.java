package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        //配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://124.222.230.147:6379").setPassword("Nzzwd050924.");
        //创建Redisson对象
        return Redisson.create(config);
    }

    @Bean
    public RedissonClient redissonClient2() {
        //配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://124.222.230.147:6380").setPassword("Nzzwd050924.");
        //创建Redisson对象
        return Redisson.create(config);
    }


    @Bean
    public RedissonClient redissonClient3() {
        //配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://124.222.230.147:6381").setPassword("Nzzwd050924.");
        //创建Redisson对象
        return Redisson.create(config);
    }
}
