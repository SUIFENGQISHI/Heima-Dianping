package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_VOUCHER_SCRIPT;
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private IVoucherOrderService proxy;

    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.execute(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {

                try {
                    //获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        private void handleVoucherOrder(VoucherOrder voucherOrder) throws InterruptedException {
            Long userId = voucherOrder.getUserId();
            String key = "lock:" + "order:" + userId + ":" + voucherOrder.getVoucherId();
            //获取锁
            RLock lock = redissonClient.getLock(key);
            boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
            if (!isLock) {
                throw new RuntimeException("服务器异常");
            }
            try {
                //使用代理对象保证事务能够触发
                proxy.createOrder(voucherOrder);
            } finally {
                lock.unlock();
            }
        }
    }

    static {
        SECKILL_VOUCHER_SCRIPT = new DefaultRedisScript<>();
        SECKILL_VOUCHER_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_VOUCHER_SCRIPT.setResultType(Long.class);
    }

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public long seckillVoucher(Long voucherId) throws InterruptedException {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_VOUCHER_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        //判断结果是否为0
        //不为0，根据返回结果抛出异常信息
        if (result != 0) {
            if (result == 1) {
                throw new RuntimeException("库存不足");
            } else {
                throw new RuntimeException("请勿重复下单");
            }
        }
        //为0，有购买资格，把下单信息保存到阻塞队列
        //保存阻塞队列
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = VoucherOrder.builder()
                .id(orderId)
                .userId(userId)
                .voucherId(voucherId)
                .payType(1)
                .status(1)
                .build();
        orderTasks.add(voucherOrder);
        //获取代理对象
        this.proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单ID
        return orderId;
    }
//    @Override
//    public long seckillVoucher(Long voucherId) throws InterruptedException {
//        //查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断秒杀是否开始,未开始，返回错误信息
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            throw new RuntimeException("秒杀未开始");
//        }
//        //判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            throw new RuntimeException("秒杀已经结束");
//        }
//        //判断库存是否充足
//        if (voucher.getStock() < 1) {
//            throw new RuntimeException("库存不足");
//        }
//
//        //库存充足，下单
//        Long userId = UserHolder.getUser().getId();
//        String key = "lock:" + "order:" + userId + ":" + voucherId;
//        //获取锁
//        RLock lock = redissonClient.getLock(key);
//        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
//        if (!isLock) {
//            throw new RuntimeException("服务器异常");
//        }
//        try {
//            //使用代理对象保证事务能够触发
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }

    @Transactional
    public void createOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();

        //该用户已购买过该优惠券，抛出异常
        if (count > 0) {
            throw new RuntimeException("您已购买过该优惠券，请勿重复购买");
        }
        //扣除库存并创建订单
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            throw new RuntimeException("库存不足");
        }
        save(voucherOrder);
    }


}
