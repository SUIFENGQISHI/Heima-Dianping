package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ShopServiceImpl resultService;

    public void setWithTime(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = RedisData.builder()
                .data(value)
                .expireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)))
                .build();
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //缓存穿透
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        //从Redis查询商铺缓存
        String resultJson = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        //判断是否存在且不为空
        if (StrUtil.isNotBlank(resultJson)) {
            //存在，直接返回
            return JSONUtil.toBean(resultJson, type);
        }
        //存在，但是是空值，返回错误信息
        if (resultJson != null) {
            return null;
        }
        //不存在，根据ID查询数据库，判断是否存在
        R result = dbFallback.apply(id);
        //不存在，设置空值到redis，抛出异常
        if (result == null) {
            this.setWithTime(keyPrefix + id, "", time, timeUnit);
            return null;
        }
        //存在，存入Redis，然后返回
        this.setWithTime(keyPrefix + id, result, time, timeUnit);
        return result;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = java.util.concurrent.Executors.newFixedThreadPool(10);

    //缓存击穿逻辑过期方法
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        //从Redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        //未命中，返回空
        if (StrUtil.isBlank(json)) {
            return null;
        }
        //命中，判断缓存逻辑时间是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R result = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        //未过期，返回商铺信息
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return result;
        }
        //过期，尝试获取互斥锁
        boolean isLock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
        //成功，开启独立新线程，实现缓存重建
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //缓存重建
                    //查数据库
                    R r = dbFallback.apply(id);
                    //写入redis
                    setWithLogicalExpire(keyPrefix + id, r, time, timeUnit);
                } catch (Exception e) {
                    log.error("缓存重建失败，店铺ID: {}", id, e);
                } finally {
                    //释放锁
                    unlock(RedisConstants.LOCK_SHOP_KEY + id);
                }
            });
        }

        //失败，返回旧信息
        Object data = redisData.getData();
        if (data == null) {
            return null;
        }
        return result;
    }

    private boolean tryLock(String key) {
        return BooleanUtil.isTrue(stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS));
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


}
