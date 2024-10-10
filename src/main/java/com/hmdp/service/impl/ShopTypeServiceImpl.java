package com.hmdp.service.impl;

import ch.qos.logback.core.util.TimeUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getShopTypeList() {
        // 1. 在Redis缓存中查询店铺类型的List
        String shopTypesKey = CACHE_SHOP_TYPE_KEY;
        String shopTypesJSON = stringRedisTemplate.opsForValue().get(shopTypesKey);

        // 2. 命中了缓存, 直接返回List
        List<ShopType> shopTypeList = null;
        if(shopTypesJSON != null && !shopTypesJSON.isEmpty()){
            shopTypeList = JSONUtil.toList(shopTypesJSON, ShopType.class);
            return Result.ok(shopTypeList);
        }

        // 3. 未命中缓存, 去数据库中查
        shopTypeList = query().orderByAsc("sort").list();

        // 4. 数据库中不存在, 返回错误信息
        if(shopTypeList == null || shopTypeList.isEmpty()){
            return Result.fail("不存在任何商铺类型");
        }

        // 5. 将数据库查询到的数据放进Redis缓存
        String shopTypeListJSON = JSONUtil.toJsonStr(shopTypeList);
        stringRedisTemplate.opsForValue().set(shopTypesKey, shopTypeListJSON, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);

        // 6. 返回List
        return Result.ok(shopTypeList);
    }
}
