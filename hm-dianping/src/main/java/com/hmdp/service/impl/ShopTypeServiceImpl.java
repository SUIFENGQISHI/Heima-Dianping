package com.hmdp.service.impl;

import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import cn.hutool.json.JSONUtil;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryBySort() {
        // 先从redis中查询店铺类型列表
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        List<String> jsonList = stringRedisTemplate.opsForList().range(key, 0, -1);
        
        // 若存在，直接返回
        if (jsonList != null && !jsonList.isEmpty()) {
            // 将JSON字符串列表转换为ShopType对象列表
            return jsonList.stream()
                    .map(json -> JSONUtil.toBean(json, ShopType.class))
                    .collect(Collectors.toList());
        }
        
        // 若不存在，从数据库中查询
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        
        // 将查询结果写入redis
        if (shopTypes != null && !shopTypes.isEmpty()) {
            // 将ShopType对象列表转换为JSON字符串列表
            List<String> jsonStringList = shopTypes.stream()
                    .map(JSONUtil::toJsonStr)
                    .collect(Collectors.toList());
            
            // 存入Redis的List结构
            stringRedisTemplate.opsForList().rightPushAll(key, jsonStringList);
            // 设置过期时间
            stringRedisTemplate.expire(key, RedisConstants.CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        }
        
        // 返回店铺类型列表
        return shopTypes;
    }
}
