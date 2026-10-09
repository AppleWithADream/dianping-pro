package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * MVC 配置类：负责注册两个核心拦截器，实现"登录校验 + Token 自动续期"功能。
 *
 * 双拦截器分工：
 *  1. RefreshTokenInterceptor（order = 0，先执行）
 *     - 只要请求到达，就尝试从 Redis 中刷新 Token 的过期时间（滑动过期）；
 *     - 命中 Redis 中的用户则把用户信息保存到 ThreadLocal，供后续业务直接取用；
 *     - 永不拦截请求（放行所有路径），真正的拦截判断交给下一个拦截器。
 *  2. LoginInterceptor（order = 1，后执行）
 *     - 从 ThreadLocal 判断当前请求是否已登录，未登录则拦截并返回 401；
 *     - 借助 excludePathPatterns 放行无需登录即可访问的公开接口。
 *
 * 为什么登录校验要拆成两个拦截器？
 *  如果只用一个拦截器，未登录请求（如 /shop/**）会被直接拦截，
 *  根本走不到"刷新 Token"的逻辑，导致登录用户的 Token 无法随请求续期。
 *  拆开后，刷新逻辑永远先执行、且放行一切请求，从而实现"用户只要在操作，登录态就一直有效"。
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    // @Configuration 是让本类被 Spring 扫描并加载为配置类的关键注解，
    // 若缺失，下面的 addInterceptors 将永远不会被调用，拦截器全部失效。
    // 通过实现 WebMvcConfigurer 接口（而非使用 @EnableWebMvc）重写回调方法，
    // 可以在不覆盖 Spring MVC 默认配置的前提下，向容器补充自定义拦截器。

    // 从 Spring 容器中注入 StringRedisTemplate，供 RefreshTokenInterceptor 读写 Redis 使用。
    // 注意：拦截器是通过 new 关键字手动创建的（不归 Spring 管理、无法自动注入），
    // 因此只能把容器里的 Bean 通过构造器参数传给拦截器。
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        // ============ 拦截器二：登录校验拦截器（order = 1，后执行） ============
        // 拦截所有请求，仅放行下方白名单中的路径，其余请求必须已登录。
        registry.addInterceptor(new LoginInterceptor())
                // 白名单（公开接口，无需登录即可访问）：
                //   /user/login、/user/code —— 登录、发送短信验证码
                //   /blog/hot               —— 首页热门探店笔记
                //   /shop/**                —— 商铺查询/缓存预热相关接口
                //   /voucher/**             —— 优惠券查询与秒杀相关接口
                //   /shop-type/**           —— 商铺类型列表
                //   /upload/**              —— 图片上传
                .excludePathPatterns("/user/login", "/user/code","/blog/hot", "/shop/**", "/voucher/**", "/shop-type/**","/upload/**")
                // order 值越小优先级越高、越先执行；这里设为 1，保证它排在刷新拦截器之后
                .order(1);

        // ============ 拦截器一：Token 刷新拦截器（order = 0，先执行） ============
        // 先于登录校验执行：从 Redis 读取 Token → 刷新有效期 → 将用户存入 ThreadLocal；
        // 注意该拦截器没有配置 excludePathPatterns，即放行所有路径、从不拦截请求，
        // 因为"刷新登录态"是每个请求都应执行的前置动作，是否拦截由 LoginInterceptor 决定。
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);
    }
}
