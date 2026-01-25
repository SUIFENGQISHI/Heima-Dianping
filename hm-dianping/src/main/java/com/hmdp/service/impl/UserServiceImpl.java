package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public String sendCode(String phone, HttpSession session) {
        //校验手机号，若不符合，返回错误信息
        if (RegexUtils.isPhoneInvalid(phone)) {
            return "手机号码格式错误";
        }
        //符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存验证码到Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码
        log.debug("发送验证码成功，验证码：{}", code);
        return "发送成功";
    }

    @Override
    public String login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号，若不符合，返回错误信息
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return "手机号码格式错误";
        }
        //从Redis获取验证码并校验
        String code = loginForm.getCode();
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        log.info("验证码：{}", cacheCode);
        if (cacheCode == null || !cacheCode.equals(code)) {
            return "验证码错误";
        }
        //若一致，从数据库中查询用户
        User user = query().eq("phone", phone).one();
        //若用户不存在，创建新用户并保存
        if (user == null) {
            user = createUserWithPhone(phone);
            save(user);
        }
        //保存用户信息到redis中
        session.setAttribute("user", user);
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = new UserDTO();
        BeanUtil.copyProperties(user, userDTO);
        // 将所有值转换为String，避免StringRedisTemplate的类型转换错误
        Map<String, Object> map = BeanUtil.beanToMap(userDTO, new java.util.HashMap<>(),
            cn.hutool.core.bean.copier.CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString()));
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, map);
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return token;
    }

    //  通过手机号创建新用户
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("momo_" + RandomUtil.randomString(10));
        return user;
    }
}
