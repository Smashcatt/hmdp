package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIDWorker redisIDWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // Lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    // 线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    // 代理对象
    private IVoucherOrderService proxy;

    // 初始化Lua脚本相关信息
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        // 设置Lua脚本文件的地址
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        // 设置返回值类型
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 初始化异步线程
    // 项目一旦启动后,任何时刻都可能有用户抢购秒杀券,所以我们需要在类初始化后就准备好这个异步线程,
    // 让它随时待命准备和数据库交互
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 线程异步下单更新数据库的线程
    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while(true){
                try {
                    // 1. 获取消息队列中的消息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("consumerGroup", "consumer1"), // 指定消费者组和消费者
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)), // 指定查询数量和阻塞时长
                            StreamOffset.create(queueName, ReadOffset.lastConsumed()) // 指定读取哪个队列的消息和消息的起始id
                    );

                    // 2. 判断消息获取是否成功
                    // 若消息获取失败, 则表示没有消息,进入下一次循环
                    if(list == null || list.isEmpty()) continue;

                    // 3. 获取消息成功, 将消息解析成 VoucherOrder 对象
                    MapRecord<String, Object, Object> msg = list.get(0);
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(msg.getValue(), new VoucherOrder(), true);

                    // 4. 获取消息成功, 将订单信息加进数据库
                    handleVoucherOrder(voucherOrder);

                    // 5. 消息处理完了, 对消息进行确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "consumerGroup", msg.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);

                    // 6. 除了异常, 从pending-list中获取消息并处理
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while(true){
                try {
                    // 1. 获取消息队列中的消息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("consumerGroup", "consumer1"), // 指定消费者组和消费者
                            StreamReadOptions.empty().count(1), // 指定查询数量
                            StreamOffset.create(queueName, ReadOffset.from("0")) // 指定读取哪个队列的消息和消息的起始id
                    );

                    // 2. 判断消息获取是否成功
                    // 若消息获取失败, 则表示pending-list中没有消息, 终止循环
                    if(list == null || list.isEmpty()) break;

                    // 3. 获取消息成功, 将消息解析成 VoucherOrder 对象
                    MapRecord<String, Object, Object> msg = list.get(0);
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(msg.getValue(), new VoucherOrder(), true);

                    // 4. 获取消息成功, 将订单信息加进数据库
                    handleVoucherOrder(voucherOrder);

                    // 5. 消息处理完了, 对消息进行确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "consumerGroup", msg.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1. 获取用户id
        Long userId = voucherOrder.getUserId();
        // 2. 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:"+userId);
        // 3. 获取锁
        boolean isLock = lock.tryLock();
        // 4. 判断是否获取锁成功
        if(!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            //获取代理对象
            proxy.generateVoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
    }

    @Override
    public Result secKillVoucher(Long voucherId) {
        // 1. 获取用户id和订单id
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIDWorker.nextID("order");
        // 2. 执行Lua脚本
        Long isViable = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                userId.toString(), voucherId.toString(), String.valueOf(orderId)
        );
        // 3. 判断用户是否可以购买
        if(isViable.intValue() != 0){
            return Result.fail((isViable == 1) ?
                    "下单失败, 库存不足": "下单失败, 对于同一秒杀券, 同一用户只能购买一次");
        }
        // 4. 初始化代理对象
        proxy = (IVoucherOrderService)AopContext.currentProxy();
        // 5. 返回订单id
        return Result.ok(orderId);
    }

    @Transactional
    public void generateVoucherOrder(VoucherOrder voucherOrder){
        // 1. 判断用户是否已经购买过秒杀券
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count>0){
            // 用户已经购买过了
            log.error("用户已经购买过一次！");
            return;
        }

        // 2. 用户没购买过, 且库存充足, 扣减数据库中的库存信息
        // 获取Redis中库存信息
        String stock = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId);
        int stockCount = Integer.parseInt(stock);
        // 更新数据库
        LambdaUpdateWrapper<SeckillVoucher> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(SeckillVoucher::getStock, stockCount)
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .gt(SeckillVoucher::getStock, 0);
        boolean isSuccess = seckillVoucherService.update(wrapper);
        if(!isSuccess){
            log.error("库存不足!");
            return;
        }

        // 3. 保存订单信息至数据库
        save(voucherOrder);
    }
}
