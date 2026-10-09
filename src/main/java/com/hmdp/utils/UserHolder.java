package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

/**
 * 线程上下文工具类：基于 ThreadLocal 保存当前请求的登录用户。
 * 一次请求由 Tomcat 的同一个线程处理，拦截器存入、业务层取用、请求结束后清理，
 * 从而避免在每个方法里反复传递 userId。
 */
public class UserHolder {
    // 每个线程一份独立副本，互不干扰
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    // 保存当前登录用户到 ThreadLocal
    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    // 获取当前登录用户，未登录返回 null
    public static UserDTO getUser(){
        return tl.get();
    }

    // 请求结束时移除，防止线程复用导致的用户串号与内存泄漏
    public static void removeUser(){
        tl.remove();
    }
}
