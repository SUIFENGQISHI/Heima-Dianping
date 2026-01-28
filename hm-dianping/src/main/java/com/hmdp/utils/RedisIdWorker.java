package com.hmdp.utils;


import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.jni.Local;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
@Slf4j
public class RedisIdWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final Long BEGIN_TIMESTAMP = 1767225600L;
    private static final int COUNT_BITS = 32;

    public Long nextId(String keyPrefix) {
        //生成时间戳
        Long nowSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        Long nowTimestamp = nowSecond - BEGIN_TIMESTAMP;

        //生成序列号
        //获取当天日期，精确到天
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));

        //自增长

        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        //拼接并返回

        return nowTimestamp << COUNT_BITS | count;
    }


}
