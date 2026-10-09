package com.hmdp.utils;


import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/**
 * 登录校验拦截器（order=1）：负责判断当前请求是否已登录。
 * 真正的用户信息由 RefreshTokenInterceptor 提前放入 ThreadLocal，这里只做"有无"判断，
 * 未登录则返回 401 并拦截请求。
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //判断是否需要拦截(ThreadLocal中是否有用户)
        if (UserHolder.getUser() == null){
            response.setStatus(401);
            return false;
        }
        //返回true，表示放行
        return true;
    }

}
