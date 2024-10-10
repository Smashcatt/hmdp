package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result getShopById(Long id) {
        Shop shop = queryWithMutex(id);
        if(shop == null){
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }


    public Shop queryWithLogicalExpire(Long id){
        String shopKey = CACHE_SHOP_KEY + id;
        String lockKey = LOCK_SHOP_KEY + id;
        // 1. 获取商铺缓存数据
        String shopJSON = stringRedisTemplate.opsForValue().get(shopKey);
        // 2. 未命中缓存, 返回空
        if(StrUtil.isBlank(shopJSON)){
            return null;
        }
        // 3. 命中缓存
        RedisData redisData = JSONUtil.toBean(shopJSON, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        // 4. 缓存没逻辑过期, 直接返回商铺信息
        if(LocalDateTime.now().isBefore(redisData.getExpireTime())){
            return shop;
        }
        // 5. 缓存逻辑过期了, 尝试获取锁重建缓存
        boolean isSuccess = tryLock(lockKey);
        // 6. 获取锁失败, 直接返回旧数据
        if(!isSuccess){
            return shop;
        }
        // 7. 获取锁成功, 开启新线程重建缓存, 同时返回旧数据
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try{
                this.saveShopToRedisBeforehand(id, CACHE_SHOP_TTL);
            }catch (Exception e){
                throw new RuntimeException(e);
            }finally {
                // 8. 缓存重建完成, 释放锁
                removeLock(lockKey);
            }
        });
        // 9. 返回过期的商铺信息
        return shop;
    }
    /**
     * @param id 热点店铺的id
     * @param expireSeconds 在缓存中存放多少秒
     */
    public void saveShopToRedisBeforehand(Long id, Long expireSeconds){
        // 1. 查询店铺
        Shop shop = getById(id);
        if(shop == null){
            throw new RuntimeException("该店铺不存在");
        }
        // 2. 封装逻辑过期字段
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3. 放进缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    public Shop queryWithMutex(Long id){
        String shopKey = CACHE_SHOP_KEY + id;
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try{
            while(true){
                // 1. 从Redis中查询商铺缓存信息
                // 这里用opsForHash也可以, 但是为了方便展示Redis的多种用法, 这里使用opsForValue
                // 对于对象, 获取的是JSON格式的字符串
                String shopJSON = stringRedisTemplate.opsForValue().get(shopKey);

                // 2. 命中了缓存, 返回商铺信息
                if(StrUtil.isNotBlank(shopJSON)){
                    shop = JSONUtil.toBean(shopJSON, Shop.class); // 将JSON格式的字符串转成类对象
                    return shop;
                }

                // 3. 未命中缓存, 即shopJSON是"", 尝试获取互斥锁
                // 因为isNotBlank()只有当参数是"abc"才会返回true, 若参数是""、null、"\t\n"都会返回false
                if (shopJSON == null) {
                    if (tryLock(lockKey)) {
                        break; // 获取互斥锁成功, 结束循环去数据库
                    } else {
                        Thread.sleep(50); // 获取互斥锁失败, 休眠一会儿进入下一次循环
                    }
                } else {
                    return null; // shopJSON=="", 即命中了我们存入的空值, 返回空
                }
            }

            // 4. shopJSON是null值, 去数据库中查
            shop = getById(id);

            // 5. 数据库中没有, 将null值放进缓存并设置过期时间
            if(shop == null){
                stringRedisTemplate.opsForValue().set(shopKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            //6. 数据库中有, 将数据库中的数据转成JSON字符串存进Redis缓存
            stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop));

        }catch (InterruptedException e){
            e.printStackTrace();
        }finally {
            // 7. 释放锁
            removeLock(lockKey);
        }

        // 8. 返回商铺信息
        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void removeLock(String key){
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if ( id == null) {
            return Result.fail("店铺id不能为空");
        }

        // 1. 更新数据库
        updateById(shop);

        // 2. 更新缓存
        String shopKey = CACHE_SHOP_KEY + id;
        stringRedisTemplate.delete(shopKey);

        return Result.ok();
    }
}
