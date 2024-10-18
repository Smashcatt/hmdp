package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author Shane
 * @create 2024/10/11 - 11:33
 */
@Component
public class RedisIDWorker {
    // 起始时间戳, 表示2024年1月1日0时0分0秒
    private static final long BEGIN_TIMESTAMP = 1704067200L;
    // 序列号的位数
    private static final int COUNT_BITS = 32;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * @param keyPrefix id的前缀,用于区分不同业务
     * @return 自增长的id
     */
    public long nextID(String keyPrefix){
        // 1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        // 2. 生成序列号
        // Redis单个key自增长的值是有一个上限的,所以key的序列号部分不能一直用同一个,要有一点变化,
        // 所以我们还需要给key的序列号部分拼接一个日期信息
        // 2.1. 获取当前日期,精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2. 自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3. 拼接并返回
        // timeStamp << COUNT_BITS：将时间戳左移32位, 因为后32位是留给真正的自增id的, 后32位此时是0
        // | count：将左移的时间戳和count进行或运算, 即将32个0和二进制的count进行或运算, 有1就返回1, 都是0就返回0
        return timeStamp << COUNT_BITS | count;
    }
}
