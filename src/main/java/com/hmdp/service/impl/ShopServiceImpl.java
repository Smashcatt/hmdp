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
import com.hmdp.utils.CacheClient;
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
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result getShopById(Long id) {
        // 解决缓存穿透
        Shop shop = cacheClient.getSolvingCachePenetration(CACHE_SHOP_KEY, id, Shop.class, Long.class,
                this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 解决缓存击穿
//        Shop shop = cacheClient.getSolvingCacheBreakdown(CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class, Long.class,
//                this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if(shop == null){
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
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
