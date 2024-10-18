package com.hmdp;

import com.hmdp.config.RedissonConfig;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private RedissonClient redissonClient;
    @Test
    void testRedisson() throws InterruptedException {
        // 1. 创建锁对象, 同时指定锁的名称
        RLock lock = redissonClient.getLock("myLock");
        // 2. 尝试获取锁
        // 参数1：获取锁的最大等待时间, 期间会一直重试
        // 参数2：锁的自动释放时间  参数3：时间单位
        boolean isSuccess = lock.tryLock(1, 10, TimeUnit.SECONDS);
        // 3. 判断释放获取锁成功
        if(isSuccess){
            try{
                System.out.println("执行业务");
            }finally{
                // 释放锁
                lock.unlock();
            }
        }
    }
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private RedisIDWorker redisIDWorker;
    @Test
    void testIcrID(){

    }

    @Test
    void testSaveShop(){
        shopService.saveShopToRedisBeforehand(3L, 10L);
    }

}
