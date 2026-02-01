package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {
    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private static final String lock_prefix = "lock:";
    private static final String thread_prifix = "thread:";
    private final String key = lock_prefix + name;
    private final String threadId = String.valueOf(Thread.currentThread().getId());
    private final String value = thread_prifix + threadId;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean trylock(long timeoutSec) {
        //获取锁
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(key, value, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(isLock);
    }

    @Override
    public void unlock() {
        //释放锁
        stringRedisTemplate.delete(key);

    }
}
