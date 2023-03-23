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
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
//        Shop shop = queryWithPassThrough(id);

        //使用互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

        //基于逻辑过期解决缓存击穿问题
        Shop shop = queryWithLogicalExpire(id);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok();
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null) {
            return Result.fail("店铺id不能为空");
        }

        String shopKey = CACHE_SHOP_KEY + shop.getId();
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(shopKey);

        return Result.ok();
    }
    /*线程池*/
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /*基于逻辑过期解决缓存击穿问题*/
    public Shop queryWithLogicalExpire(Long id){
        String shopKey = CACHE_SHOP_KEY + id;
        // 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        // 判断是否命中
        if (StrUtil.isBlank(shopJson)) {
            // 未命中，直接返回
            return null;
        }
        // 命中，需要先将json反序列化成对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            // 未过期，直接返回店铺信息
            return shop;
        }

        // 已过期，需要缓存重建
        // 缓存重建
        // 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 获取锁成功
        if(isLock){
            // 开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                // 重建缓存
                try {
                    this.saveShop2Redis(id, 30L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unLock(lockKey);
                }
            });
            return shop;
        }
        // 返回过期的商铺信息
        return shop;
    }
    /*基于互斥锁解决缓存击穿问题*/
    public Shop queryWithMutex(Long id){
        String shopKey = CACHE_SHOP_KEY + id;
        // 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        // 判断是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 命中，redis存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中是否是空值
        if (shopJson == null) {
            //返回错误信息
            return null;
        }
        // 开始实现缓存重建
        // 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 判断是否获取成功
            if (!isLock){
                // 获取失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 获取成功
            // 二次确认
            String doubleCheckKey = stringRedisTemplate.opsForValue().get(shopKey);
            if (StrUtil.isNotBlank(doubleCheckKey)){
                return JSONUtil.toBean(doubleCheckKey, Shop.class);
            }
            // 根据id查询数据库
            shop = getById(id);
            // 模拟重建延迟
            Thread.sleep(200);
            // 数据库不存在，返回错误
            if (shop == null) {
                // 解决缓存穿透
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(shopKey,"", CACHE_NULL_TTL , TimeUnit.MINUTES);
                return null;
            }
            // 数据库存在，写入redis
            stringRedisTemplate.opsForValue().set(shopKey,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL , TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 释放互斥锁
            unLock(lockKey);
        }
        // 返回
        return shop;
    }
    /*解决缓存穿透*/
    public Shop queryWithPassThrough(Long id){
        String shopKey = CACHE_SHOP_KEY + id;
        // 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        // 判断是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 命中，redis存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中是否是空值
        if (shopJson == null) {
            //返回错误信息
            return null;
        }
        // 没命中，不存在，根据id查询数据库
        Shop shop = getById(id);
        // 数据库不存在，返回错误
        if (shop == null) {
            // 解决缓存穿透
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(shopKey,"", CACHE_NULL_TTL , TimeUnit.MINUTES);
            return null;
        }
        // 数据库存在，写入redis
        stringRedisTemplate.opsForValue().set(shopKey,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL , TimeUnit.MINUTES);
        // 返回
        return shop;
    }
    /*缓存预热，向redis添加逻辑过期时间的数据*/
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 查询店铺的数据
        Shop shop = getById(id);
        //模拟缓存重建的延迟
        Thread.sleep(200);
        // 封装成逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
    /*获取锁*/
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    /*释放锁*/
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
