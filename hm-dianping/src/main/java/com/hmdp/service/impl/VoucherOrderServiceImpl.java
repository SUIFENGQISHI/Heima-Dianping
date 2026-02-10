package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private IVoucherOrderService proxy;

    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.execute(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.order";

        @Override
        public void run() {
            while (true) {
                try {
                    //获取消息队列中的订单信息 xreadgroup group g1 c1 count 1 block streams streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        //如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> map = list.get(0);
                    Map<Object, Object> value = map.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //如果获取成功，创建订单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认，表明该条信息已被消费
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", map.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    try {
                        handlePendingList();
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }

        private void handlePendingList() throws InterruptedException {
            while (true) {
                try {
                    //获取pel中的订单信息 xreadgroup group g1 c1 count 1  streams streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        //如果获取失败，说明pel中没有异常消息，结束循环
                        break;
                    }
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> map = list.get(0);
                    Map<Object, Object> value = map.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //如果获取成功，创建订单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认，表明该条信息已被消费
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", map.getId());
                } catch (Exception e) {
                    log.error("订单状态异常", e);
                    Thread.sleep(50);
                }
            }
        }
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    private class VoucherOrderHandler implements Runnable {
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    //获取队列中的订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    //创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (InterruptedException e) {
//                    throw new RuntimeException(e);
//                }
//            }
//        }

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
        long orderId = redisIdWorker.nextId("order");
        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_VOUCHER_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
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
//        //为0，有购买资格，把下单信息保存到阻塞队列
//        //保存阻塞队列
//        VoucherOrder voucherOrder = VoucherOrder.builder()
//                .id(orderId)
//                .userId(userId)
//                .voucherId(voucherId)
//                .payType(1)
//                .status(1)
//                .build();
//        orderTasks.add(voucherOrder);
        //获取代理对象
        this.proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单ID
        return orderId;
    }
//    @Override
//    public long seckillVoucher(Long voucherId) throws InterruptedException {
//        //获取用户
//        Long userId = UserHolder.getUser().getId();
//        //执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_VOUCHER_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(),
//                userId.toString()
//        );
//        //判断结果是否为0
//        //不为0，根据返回结果抛出异常信息
//        if (result != 0) {
//            if (result == 1) {
//                throw new RuntimeException("库存不足");
//            } else {
//                throw new RuntimeException("请勿重复下单");
//            }
//        }
//        //为0，有购买资格，把下单信息保存到阻塞队列
//        //保存阻塞队列
//        long orderId = redisIdWorker.nextId("order");
//        VoucherOrder voucherOrder = VoucherOrder.builder()
//                .id(orderId)
//                .userId(userId)
//                .voucherId(voucherId)
//                .payType(1)
//                .status(1)
//                .build();
//        orderTasks.add(voucherOrder);
//        //获取代理对象
//        this.proxy = (IVoucherOrderService) AopContext.currentProxy();
//        //返回订单ID
//        return orderId;
//    }


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
