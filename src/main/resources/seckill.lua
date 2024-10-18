-- 1. 参数列表
-- 1.1. 用户id
local userId = ARGV[1]
-- 1.2. 优惠券id
local voucherId = ARGV[2]

-- 2. 数据key
-- 2.1. 库存key
-- 在Lua脚本中, ".."等同于Java中的"+"
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2. 订单key
local orderKey = 'seckill:order:' .. voucherId

-- 3. 脚本业务
-- 3.1. 判断库存是否充足
-- call方法返回的是字符串,无法与0进行比较,所以要转成数字
if(tonumber(redis.call('get',stockKey)) <= 0) then
    --3.1.2.库存不足，返回1
    return 1
end

--3.2.判断用户是否下单
-- SISMEMBER key member, 该命令用于判断key对应的set集合内是否存在member, 存在返回1, 不存在返回0
if(redis.call('sismember',orderKey,userId)==1) then
    --3.2.1.存在，说明是重复下单，返回2
    return 2
end

--3.3. 扣减库存
redis.call('incrby',stockKey,-1)
--3.4. 添加下单用户数据至Redis
redis.call('sadd',orderKey,userId)
return 0