package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.aop.framework.AopProxy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIDWorker redisIDWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        // 设置Lua脚本文件的地址
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        // 设置返回值类型
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result secKillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1. 执行Lua脚本
        Long isViable = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                userId.toString(), voucherId.toString()
        );

        // 2. 判断用户是否可以购买
        if(isViable == null)
            return Result.fail("下单失败, 未知异常");
        if(isViable != 0){
            return Result.fail((isViable == 1) ?
                    "下单失败, 库存不足": "下单失败, 对于同一秒杀券, 同一用户只能购买一次");
        }

        // 2. 用户有购买资格,将一系列信息存进阻塞队列, 方便异步线程获取
        long orderId = redisIDWorker.nextID("order");
        //TODO 保存进阻塞队列

        // 3. 返回订单id
        return Result.ok(orderId);
    }


    @Transactional
    public Result generateVoucherOrder(Long voucherId, Long userId, Integer stockCount){
        // 1. 判断这个用户是否已经购买过秒杀券
        LambdaQueryWrapper<VoucherOrder> lqw = new LambdaQueryWrapper<>();
        lqw.eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getVoucherId, voucherId);
        if (count(lqw) > 0) {
            // 1.1. 该用户之前已经购买过秒杀券,返回错误信息
            return Result.fail("您已经购买过该秒杀券,无法再次抢购");
        }
        // 2. 这个用户之前没买过,扣减库存,生成订单,返回订单id
        // 扣减库存
        LambdaUpdateWrapper<SeckillVoucher> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(SeckillVoucher::getStock, stockCount - 1)
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .gt(SeckillVoucher::getStock, 0); // 只需判断数据库中秒杀券的库存是否还大于0即可,无需CAS
        boolean isSuccess = seckillVoucherService.update(wrapper);
        // 数据库扣减库存失败
        if (!isSuccess) {
            return Result.fail("秒杀券已被抢光");
        }
        // 数据库扣减库存成功,生成订单并返回订单id
        VoucherOrder newOrder = new VoucherOrder(); // 生成订单
        long orderId = redisIDWorker.nextID("order");
        newOrder.setId(orderId);
        newOrder.setVoucherId(voucherId);
        newOrder.setUserId(userId);
        save(newOrder);
        return Result.ok(orderId);
    }
}
