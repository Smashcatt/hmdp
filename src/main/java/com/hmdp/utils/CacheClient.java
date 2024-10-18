package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.crypto.interfaces.PBEKey;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * @author Shane
 * @create 2024/10/10 - 16:42
 */
@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit){
        LocalDateTime expireTime = LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time));
        RedisData redisData = new RedisData(value, expireTime);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    public <R, ID> R getSolvingCachePenetration(String keyPrefix, ID id, Class<R> resultType, Class<ID> idType,
                                             Function<ID, R> dbFallBack, Long time, TimeUnit timeUnit){
        String cacheKey = keyPrefix + id;
        // 1. 获取缓存数据
        String cacheJSON = stringRedisTemplate.opsForValue().get(cacheKey);
        // 2. 命中缓存, 直接返回
        if(StrUtil.isNotBlank(cacheJSON)){
            return JSONUtil.toBean(cacheJSON, resultType);
        }
        // 3. 命中缓存, 且缓存是"", 表示该数据根本不存在, 在数据库中也不存在的那种
        if(cacheJSON != null){
            return null;
        }
        // 4. 命中缓存, 且缓存是null, 查数据库
        // 我们不知道要查哪个数据库, 但调用者肯定知道, 所以我们把这个查数据库的逻辑交给调用者
        R r = dbFallBack.apply(id);
        // 5. 数据库中不存在该数据, 缓存空值, 然后返回null
        if(r == null){
            set(cacheKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6. 数据库中存在该数据, 缓存该数据, 然后返回该数据
        set(cacheKey, r, time, timeUnit);
        return r;
    }

    public <R, ID> R getSolvingCacheBreakdown(String cacheKeyPrefix, String lockKeyPrefix, ID id, Class<R> resultType, Class<ID> idType,
                                              Function<ID, R> dbFallBack, Long expireTime, TimeUnit expireTimeUnit){
        String cacheKey = cacheKeyPrefix + id;
        String lockKey = lockKeyPrefix + id;
        // 1. 获取缓存数据
        String cacheJSON = stringRedisTemplate.opsForValue().get(cacheKey);
        // 2. 未命中缓存, 返回空
        if(StrUtil.isBlank(cacheJSON)){
            return null;
        }
        // 3. 命中缓存
        RedisData redisData = JSONUtil.toBean(cacheJSON, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, resultType);
        // 4. 缓存没逻辑过期, 直接返回商铺信息
        if(LocalDateTime.now().isBefore(redisData.getExpireTime())){
            return r;
        }
        // 5. 缓存逻辑过期了, 尝试获取锁重建缓存
        boolean isSuccess = tryLock(lockKey);
        // 6. 获取锁失败, 直接返回旧数据
        if(!isSuccess){
            return r;
        }
        // 7. 获取锁成功, 开启新线程重建缓存, 同时返回旧数据
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try{
                // 7.1. 从数据库中查询数据
                R dbData = dbFallBack.apply(id);
                if(dbData == null){
                    throw new RuntimeException("该店铺不存在");
                }
                // 7.2. 封装逻辑过期字段并放进缓存
                setWithLogicalExpire(cacheKey, dbData, expireTime, expireTimeUnit);
            }catch (Exception e){
                throw new RuntimeException(e);
            }finally {
                // 8. 缓存重建完成, 释放锁
                removeLock(lockKey);
            }
        });
        // 9. 返回过期的商铺信息
        return r;
    }
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void removeLock(String key){
        stringRedisTemplate.delete(key);
    }
}
