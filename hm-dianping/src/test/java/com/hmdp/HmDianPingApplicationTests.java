package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Test
    void testSaveShop() throws InterruptedException {
        for (int i = 1; i <= 5; i++) {
            Shop shop = shopService.getById(i);
            cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY + i, shop, 10L, TimeUnit.SECONDS);
        }
    }

    private final ExecutorService executorService = Executors.newFixedThreadPool(500);

    @Test
    void testIdGenerator() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id: " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executorService.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time: " + (end - begin));
    }

    @Test
    void testVoucherRedis() {
        stringRedisTemplate.opsForValue().set(RedisConstants.SECKILL_STOCK_KEY + "11", "200");
    }

}
