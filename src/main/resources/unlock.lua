-- redis.call('get',KEYS[1]) : 用于获取缓存中锁的key对应的value, 即获取缓存中线程的标识
-- ARGV[1] : 用于获取传过来的当前线程的标识
-- 判断 当前线程的标识 和 缓存的锁的线程标识 是否一致
if(redis.call('get',KEYS[1]) == ARGV[1]) then
    -- 一致就释放锁, return 1
    return redis.call('del',KEYS[1])
end
-- 不一致就return 0, 表示失败
return 0
