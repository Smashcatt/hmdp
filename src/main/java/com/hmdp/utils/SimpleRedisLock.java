package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author Shane
 * @create 2024/10/13 - 17:35
 */
public class SimpleRedisLock implements ILock{
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        // 设置Lua脚本文件的地址
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        // 设置返回值类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name) {
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 1. 获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 2. 尝试获取锁
        Boolean isSuccess = stringRedisTemplate.opsForValue().
                setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        // 3. 返回结果,同时解决自动拆箱可能导致的问题
        //    是true就返回true, 是false或null就返回false
        return Boolean.TRUE.equals(isSuccess);
    }

    @Override
    public void unlock() {
        // 1. 获取缓存中,锁对应的key,以便让Lua脚本获取到对应的缓存的线程标识
        List<String> list = new ArrayList<>();
        list.add(KEY_PREFIX + name);
        // 2. 获取当前线程的标识
        String currentThreadId = ID_PREFIX + Thread.currentThread().getId();
        // 3. 调用Lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT, list, currentThreadId);
   }
}
