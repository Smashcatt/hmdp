package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private UserMapper userMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getUserBasicInfo(Long id) {
        User user = getById(id);
        if(user == null){
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. Verify phone number
        if(RegexUtils.isPhoneInvalid(phone)){
            // 2. The phone number is illegal, return false info
            return Result.fail("The pattern of phone number is illegal");
        }

        // 3. The phone number is legal, then generate a code of length 6,
        String code = RandomUtil.randomNumbers(6);

        // 4. save the code in the Redis, set the duration of expiration which is 2 minutes
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5. send back the code
        // The actual operation of sending code requires a lot of hassle, so we fake it instead
        log.debug("The code has been sent successfully, code:{}", code);

        // 6. return result
        return Result.ok(code);
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号格式
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("The pattern of phone number is illegal");
        }

        // 2. 校验验证码是否正确
        String codeSent = loginForm.getCode();
        String codeCached = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        if(codeCached == null || !codeCached.equals(codeSent)){
            return Result.fail("The verification code is wrong");
        }

        // 3. 根据手机号去数据库查用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getPhone, phone);
        User userSelected = userMapper.selectOne(wrapper);
        // 用户不存在就创建一个用户然后存进数据库里
        if(userSelected == null){
            User user = new User();
            user.setPhone(phone);
            user.setNickName(USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));
            userMapper.insert(user);

            userSelected = user;
        }

        // 4. 将用户暂存进Redis,Key是随机的UUID,Value是Hash类型的值
        String token = UUID.randomUUID().toString(true);

        // 将User类型的用户转成UserDTO类型,可去除掉一些不必要的字段,节省内存
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(userSelected,userDTO);

        // 将UserDTO类型转成HashMap类型
        /*
          BeanUtil.beanToMap(userDTO) 将 UserDTO 对象转换为 Map<String, Object>，
          其中 Object 可以是 Long 类型。然而，StringRedisTemplate 使用的是 StringRedisSerializer，
          这意味着 Redis 期望 String 类型的值，而你传入的是 Object 类型的值，包括可能的 Long 类型，
          就会导致类型不匹配而报错。所以我们需要将map里的value值全部转成String类型
         */
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())

        );

        // 存储进Redis并设置过期时间为30min
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 5. 返回ok
        return Result.ok(token);
    }
}
