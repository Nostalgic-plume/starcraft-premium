-- Redis Lua 原子扣库存脚本
-- KEYS[1] : sku:stock:{skuId}
-- ARGV[1] : 扣减数量
-- 返回值 :  1=成功  0=库存不足  -1=缓存未命中（需从DB加载）
local stock = redis.call('GET', KEYS[1])
if not stock then
    return -1
end
local stockNum = tonumber(stock)
local deductNum = tonumber(ARGV[1])
if stockNum >= deductNum then
    redis.call('DECRBY', KEYS[1], deductNum)
    return 1
end
return 0
