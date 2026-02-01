package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {
    private StringRedisTemplate stringRedisTemplate;
    private final String name;
    private static final String lock_prefix = "lock:";
    private final static String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean trylock(long timeoutSec) {
        //获取锁
        String key = lock_prefix + name;
        String threadId = String.valueOf(Thread.currentThread().getId());
        String value = ID_PREFIX + threadId;
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(key, value, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(isLock);
    }

    @Override
    public void unlock() {
        //释放锁
        //获取标识
        String key = lock_prefix + name;
        String threadId = String.valueOf(Thread.currentThread().getId());
        String value = ID_PREFIX + threadId;
        String currentValue = stringRedisTemplate.opsForValue().get(key);
        //判断标识是否一致
        if (value.equals(currentValue)) {
            stringRedisTemplate.delete(key);
        }

    }
}
