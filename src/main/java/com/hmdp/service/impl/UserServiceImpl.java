package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * 用户服务实现类：负责发送验证码、登录、签到与连续签到统计。
 * 登录态不使用 Session，而是把用户信息存 Redis（Hash），用 token 标识，
 * 由拦截器滑动续期。
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 发送手机验证码
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，返回失败
            return Result.fail("手机号格式无效");
        }
        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到Redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码
        log.debug("发送验证码成功，验证码：{}", code);
        //返回 ok
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            //如果不符合，返回失败
            return Result.fail("手机号格式无效");
        }
        // 2.校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)){
            //3.如果不一致，返回失败
            return Result.fail("验证码无效");
        }
        // 4.一致,根据手机号查询用户
        User user = query().eq("phone", phone).one();
        // 5.如果不存在，创建用户
        if (user == null) {
            // 6.不存在，创建新用户
            user = createUserWithPhone(phone);
        }
        // 7.保存用户信息到Redis中
        // 7.1生成token
        String token = UUID.randomUUID().toString();
        // 7.2保存userDTO
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        // 7.3存储到Redis
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        // 7.4设置过期时间
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, 30, TimeUnit.MINUTES);
        // 7.5 返回token
        return Result.ok(token);
    }

    /**
     * 用户签到：用 Redis Bitmap 记录当月签到，每一位代表一天（第几天即第几位）。
     */
    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.拼接 key：sign:{userId}:yyyyMM
        LocalDateTime now = LocalDateTime.now();
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + format;
        // 3.将"今天"对应位设为 1（日期从 1 开始，位图下标从 0 开始，故 dayOfMonth - 1）
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * 统计当月截至今天的连续签到天数。
     */
    @Override
    public Result signCount() {
        // 1.拼接 key
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String format = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + format;
        int dayOfMonth = now.getDayOfMonth();
        // 2.取出本月 1 号到今天的位图（共 dayOfMonth 位），bitField 以十进制整数返回
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.size() == 0) {
            return Result.fail("签到数据不存在");
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.fail("签到数据不存在");
        }
        // 3.从最低位（今天）开始向右数，遇到 1 就计数、遇到 0 就停止，得到连续签到天数
        int count = 0;
        while (true) {
            // 看最低位是否为 1
            if ((num & 1) == 0) {
                break;
            }
            count++;
            // 无符号右移一位，继续看下一天
            num >>>= 1;
        }
        return Result.ok(count);
    }

    /**
     * 根据手机号创建用户
     */
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        // 设置随机昵称
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 保存用户
        save(user);
        return user;
    }
}
