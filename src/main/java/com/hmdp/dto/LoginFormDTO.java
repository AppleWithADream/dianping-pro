package com.hmdp.dto;

import lombok.Data;

/**
 * 登录请求表单：手机号 + 验证码（验证码登录），或手机号 + 密码（密码登录）。
 */
@Data
public class LoginFormDTO {
    // 手机号
    private String phone;
    // 短信验证码
    private String code;
    // 密码（密码登录时使用）
    private String password;
}
