package com.hmdp.utils;

/**
 * 系统级常量类：与 Redis 无关的全局配置，如上传目录、分页大小等。
 */
public class SystemConstants {
    // 图片上传到 Nginx 站点的根目录，上传后由 Nginx 对外提供访问
    public static final String IMAGE_UPLOAD_DIR = "D:\\Nginx\\nginx-1.18.0\\html\\hmdp\\imgs";
    // 新用户默认昵称前缀，后接随机字符串
    public static final String USER_NICK_NAME_PREFIX = "user_";
    // 分页默认每页条数
    public static final int DEFAULT_PAGE_SIZE = 5;
    // 分页每页最大条数，防止一次拉取过多数据
    public static final int MAX_PAGE_SIZE = 10;
}
