package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result getShopType() {
        String cacheShopTypeKey = CACHE_SHOP_TYPE_KEY;
        //从redis查询商铺类型
        String shopType = stringRedisTemplate.opsForValue().get(cacheShopTypeKey);
        //判断是否存在
        if (StrUtil.isNotBlank(shopType)){
            //redis存在，返回
            List<ShopType> shopTypes = JSONUtil.toList(shopType, ShopType.class);
            return Result.ok(shopTypes);
        }
        //redis不存在，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        String toJsonStr = JSONUtil.toJsonStr(typeList);
        //数据库不存在，返回错误
        if(typeList == null){
            return Result.fail("商铺类型不存在");
        }
        //数据库存在，将信息存入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY,toJsonStr);
        //返回
        return Result.ok(typeList);
    }
}
