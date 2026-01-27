package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Shop queryById(Long id) {
//        //缓存穿透
//        Shop shop = cacheClient.
//                queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY,
//                        id,
//                        Shop.class,
//                        this::getById,
//                        RedisConstants.CACHE_SHOP_TTL,
//                        TimeUnit.MINUTES);

//        //互斥锁解决缓存击穿问题
//        Shop shop = cacheClient.queryWithMutex(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            throw new RuntimeException("店铺不存在");
        }
        return shop;
    }

    //缓存穿透
//    public Shop queryWithPassThrough(Long id) {
//        //从Redis查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
//        //判断是否存在且不为空
//        if (StrUtil.isNotBlank(shopJson)) {
//            //存在，直接返回
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        //存在，但是是空值，返回错误信息
//        if (shopJson != null) {
//            return null;
//        }
//        //不存在，根据ID查询数据库，判断是否存在
//        Shop shop = getById(id);
//        //不存在，设置空值到redis，抛出异常
//        if (shop == null) {
//            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;
//        }
//        //存在，存入Redis，然后返回
//        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return shop;
//    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = java.util.concurrent.Executors.newFixedThreadPool(10);


//    //缓存击穿逻辑过期方法
//    public Shop queryWithLogicalExpire(Long id) {
//        //从Redis查询商铺缓存
//        String redisDataJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
//        //未命中，返回空
//        if (StrUtil.isBlank(redisDataJson)) {
//            return null;
//        }
//        //命中，判断缓存逻辑时间是否过期
//        RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
//        //未过期，返回商铺信息
//        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
//            Object data = redisData.getData();
//            if (data == null) {
//                return null;
//            }
//            return ((cn.hutool.json.JSONObject) data).toBean(Shop.class);
//        }
//        //过期，尝试获取互斥锁
//        boolean isLock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
//        //成功，开启独立新线程，实现缓存重建
//        if (isLock) {
//            CACHE_REBUILD_EXECUTOR.submit(() -> {
//                try {
//                    //缓存重建
//                    saveShopToRedis(id, 20L);
//                } catch (Exception e) {
//                    log.error("缓存重建失败，店铺ID: {}", id, e);
//                } finally {
//                    //释放锁
//                    unlock(RedisConstants.LOCK_SHOP_KEY + id);
//                }
//            });
//        }
//
//        //失败，返回旧信息
//        Object data = redisData.getData();
//        if (data == null) {
//            return null;
//        }
//        return ((cn.hutool.json.JSONObject) data).toBean(Shop.class);
//    }


//    //缓存击穿互斥锁方法
//    public Shop queryWithMutex(Long id) {
//        //从Redis查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
//        //判断是否存在且不为空
//        if (StrUtil.isNotBlank(shopJson)) {
//            //存在，直接返回
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        //存在，但是是空值，返回错误信息
//        if (shopJson != null) {
//            return null;
//        }
//
//        String lockkey = RedisConstants.LOCK_SHOP_KEY + id;
//        //实现缓存重建
//        //获取互斥锁
//        boolean isLock = tryLock(lockkey);
//        //获取锁失败，则休眠后重试
//        Shop shop = null;
//        try {
//            if (!isLock) {
//                Thread.sleep(200);
//                return queryWithMutex(id);
//            }
//
//            //成功，根据id查询数据库
//            //不存在，根据ID查询数据库，判断是否存在
//            shop = getById(id);
//            Thread.sleep(200);
//            //不存在，设置空值到redis，抛出异常
//            if (shop == null) {
//                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return null;
//            }
//            //存在，存入Redis，然后返回
//            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            //释放互斥锁
//            unlock(lockkey);
//        }
//        //返回结果
//        return shop;
//    }

//    private boolean tryLock(String key) {
//        return BooleanUtil.isTrue(stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS));
//    }
//
//    private void unlock(String key) {
//        stringRedisTemplate.delete(key);
//    }

    @Override
    @Transactional
    public void update(Shop shop) {
        //判断店铺ID
        Long id = shop.getId();
        if (id == null) {
            throw new RuntimeException("店铺ID不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);

    }

//    public void saveShopToRedis(Long id, Long expireSeconds) throws InterruptedException {
//        //查询数据库
//        Shop shop = getById(id);
//        Thread.sleep(200);
//        //封装逻辑过期时间
//        RedisData redisData = RedisData.builder()
//                .data(shop)
//                .expireTime(LocalDateTime.now().plusSeconds(expireSeconds))
//                .build();
//        //将RedisData对象转为json字符串
//        String jsonStr = JSONUtil.toJsonStr(redisData);
//        //存入Redis
//        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, jsonStr);
//    }
}
