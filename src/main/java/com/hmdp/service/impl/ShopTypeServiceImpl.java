package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 店铺类型服务实现类
 *
 * 功能：查询首页展示的店铺分类列表（如：美食、KTV、酒吧……）。
 * 店铺类型属于"读多写少、数据量小、变动频率极低"的数据，
 * 非常适合整体缓存到 Redis 中，避免每次进入首页都查询数据库。
 * 因此 queryList() 与 ShopServiceImpl.queryById() 思路一致：
 * 采用"缓存旁路"模式 —— 先查 Redis，未命中再查库并回填缓存。
 * 与商铺缓存不同的是，本缓存写入时设置了 12 小时过期时间（TTL），
 * 到期后 Redis 自动删除该 key，下次查询时再重新查库并回填（被动重建）。
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    // Spring 容器注入的 StringRedisTemplate：以字符串形式读写 Redis
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    // 注意：本类已实现 IShopTypeService 接口，该注入的其实"自己"（本类的单例对象），
    // 属于冗余的自注入，可直接删除该字段、改而使用继承自 ServiceImpl 的 query() 方法，
    // 但保留写法不影响程序运行（教学中常见写法）。
    @Resource
    private IShopTypeService typeService;

    /**
     * 查询店铺类型列表（Redis 缓存 + 数据库兜底）
     * <p>
     * 流程：先查 Redis 中 key = shopType:list 的缓存 → 命中则反序列化返回；
     * 未命中则按 sort 字段升序查询数据库 → 查不到返回失败提示，
     * 查到了就整体序列化为 JSON 写入缓存，供后续请求直接命中。
     * <p>
     * 注意点：
     *  1. 缓存 TTL 为 12 小时：过期后自动失效、下次请求懒加载重建；
     *     但 TTL 期间若运营后台修改了店铺类型，前端仍会读到旧缓存（短时数据不一致，可接受）；
     *  2. 数据库为空时不写入空缓存，极端场景下存在被反复穿透查询的风险；
     *  3. key 未使用 "cache:" 统一前缀，与商铺缓存（cache:shop:{id}）的命名风格不一致。
     *
     * @return 店铺类型列表
     */
    @Override
    public Result queryList() {
        // 1. 先查 Redis 缓存：key = shopType:list，value 为整个类型列表的 JSON 字符串
        String shopTypes = stringRedisTemplate.opsForValue().get("shop:type");
        // 2. 缓存命中判断：与 ShopServiceImpl 保持一致使用 isNotBlank（非 null、非空串、非纯空白才算命中），
        //    防御空串等脏数据进入 JSONUtil.toList 导致解析异常
        if (StrUtil.isNotBlank(shopTypes)){
            // 3. 把 JSON 数组字符串反序列化为 List<ShopType> 后直接返回，不再访问数据库
            List<ShopType> shopType = JSONUtil.toList(shopTypes, ShopType.class);
            return Result.ok(shopType);
        }
        // 4. 缓存未命中 → 查询数据库：店铺类型需按 sort 字段升序展示（MyBatis-Plus 链式查询）
        List<ShopType> typeList = typeService.query().orderByAsc("sort").list();
        // 5. 数据库为空（如类型数据尚未初始化），返回业务失败提示
        if (typeList.isEmpty()){
            return Result.fail("店铺类型列表查询失败");
        }
        // 6. 数据库命中 → 将整个列表序列化为 JSON 写入 Redis 缓存（一次性缓存全部类型），
        //    并设置 12 小时过期时间：到期自动失效，避免缓存永不更新（数据一致性问题）
        stringRedisTemplate.opsForValue().set("shop:type", JSONUtil.toJsonStr(typeList),12, TimeUnit.HOURS);
        // 7. 返回查询结果
        return Result.ok(typeList);
    }
}
