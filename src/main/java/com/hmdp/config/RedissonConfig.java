package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author Greyson
 * @create 2024/10/14 - 14:30
 */
@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient(){
        // 配置
        Config config = new Config();
        // useSingleServer()：表示使用单节点的Redis,而非集群
        // 若想使用集群, 可使用useClusterServers()
        config.useSingleServer().setAddress("redis://172.23.55.91:6379").setPassword("1227489592");
        // 创建RedissonClient对象, 即客户端
        return Redisson.create(config);
    }
}
