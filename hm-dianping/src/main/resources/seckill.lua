--1.参数列表
--1.1. 优惠券ID
local voucherID = ARGV[1]
--1.2用户ID
local userID = ARGV[2]
--1.3订单ID
local orderID = ARGV[3]


--2.数据key
--2.1.库存key
local stockKey = 'seckill:stock:' .. voucherID
--2.2.订单key
local orderKey = 'seckill:order:' .. voucherID

--3.脚本业务
--3.1 判断库存是否充足 get stockKey
if (tonumber(redis.call('get', stockKey)) <= 0) then
    --3.1.1库存不足，返回1
    return 1
end
--3.2判断用户是否曾经下过单 sismember orderKey userID
if (redis.call('sismember', orderKey, userID) == 1) then
    --3.2.1存在，用户重复下单，返回2
    return 2
end
--3.4 扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
--3.5 用户下单 sadd orderKey userID
redis.call('sadd', orderKey, userID)
--3.6 发送消息到消息队列
redis.call('xadd', 'stream.order', '*', 'id', orderID, 'userId', userID, 'voucherId', voucherID)
return 0
