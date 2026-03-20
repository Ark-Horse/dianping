package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
    private CacheClient cacheClient;

    @Override
    public Result queryTypeString() {
        List<ShopType> shopTypes = cacheClient.querySingleKeyListWithL1L2PassThrough(
                RedisConstants.CACHE_SHOP_TYPE_KEY,
            ShopType.class,
                key -> query().orderByAsc("sort").list(),
                RedisConstants.CACHE_SHOP_TYPE_L1_TTL_SECONDS,
                TimeUnit.SECONDS,
                RedisConstants.CACHE_SHOP_TYPE_TTL,
                TimeUnit.MINUTES);

        if (shopTypes == null || shopTypes.isEmpty()) {
            return Result.fail("分类不存在！！！");
        }
        return Result.ok(shopTypes);
    }
}
