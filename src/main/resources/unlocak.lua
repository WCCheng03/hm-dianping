-- 比较线程提示与锁中的是否一致
-- 锁的key
local key = KEYS[1]
-- 当前线程提示
local threadId = ARGV[1]

-- 获取锁中的线程提示 get key
local id = redis.call('get', key)
-- 比较线程提示与锁中的是否一致
if(id == threadId) then
    -- 释放锁 del key
    return redis.call('del', key)
end
return 0
