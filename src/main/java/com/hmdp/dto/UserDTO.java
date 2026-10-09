package com.hmdp.dto;

import lombok.Data;

/**
 * 登录用户的精简信息：只保存前端和业务关心的最小字段，
 * 替代完整的 User 实体存入 ThreadLocal 与 Redis，避免泄露手机号、密码等敏感字段。
 */
@Data
public class UserDTO {
    // 用户id
    private Long id;
    // 昵称
    private String nickName;
    // 头像地址
    private String icon;
}
