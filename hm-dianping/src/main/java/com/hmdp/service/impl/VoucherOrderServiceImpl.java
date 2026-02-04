package com.hmdp.service.impl;

import com.hmdp.entity.SeckillVoucher;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private RedissonClient redissonClient;


    @Override
    public long seckillVoucher(Long voucherId) throws InterruptedException {
        //查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //判断秒杀是否开始,未开始，返回错误信息
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            throw new RuntimeException("秒杀未开始");
        }
        //判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("秒杀已经结束");
        }
        //判断库存是否充足
        if (voucher.getStock() < 1) {
            throw new RuntimeException("库存不足");
        }

        //库存充足，下单
        Long userId = UserHolder.getUser().getId();
        String key = "lock:" + "order:" + userId + ":" + voucherId;
        //获取锁
        RLock lock = redissonClient.getLock(key);
        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
        if (!isLock) {
            throw new RuntimeException("服务器异常");
        }
        try {
            //使用代理对象保证事务能够触发
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createOrder(voucherId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public long createOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        int count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();

        //该用户已购买过该优惠券，抛出异常
        if (count > 0) {
            throw new RuntimeException("您已购买过该优惠券，请勿重复购买");
        }
        //扣除库存并创建订单
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            throw new RuntimeException("库存不足");
        }
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = VoucherOrder.builder()
                .id(orderId)
                .userId(userId)
                .voucherId(voucherId)
                .payType(1)
                .status(1)
                .build();
        save(voucherOrder);
        return orderId;

    }


}
