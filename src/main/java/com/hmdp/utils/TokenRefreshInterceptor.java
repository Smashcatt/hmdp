package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * @author Shane
 * @create 2024/10/8 - 16:28
 */
@Component
public class TokenRefreshInterceptor implements HandlerInterceptor {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 获取请求头中的token
        String token = request.getHeader("authorization");
        // token不存在,放行给下一个拦截器
        if(StrUtil.isBlank(token)){
            return true;
        }

        // 2. 根据token获取Redis中缓存的用户信息
        String tokenKey = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        // 对于entries,若value不存在,不会返回null,而是返回一个空,所以我们只需判断空
        if(userMap.isEmpty()){
            return true; // 用户不存在,放行给下一个拦截器
        }

        // 3. 将Map类型的用户对象转成UserDTO类型
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        // 4. 将用户保存至ThreadLocal
        // UserHolder类里封装了一个ThreadLocal
        UserHolder.saveUser(userDTO);

        // 5. 刷新token的有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 6. 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // Controller将数据响应给前端后,释放ThreadLocal,避免内存泄露
        UserHolder.removeUser();
    }
}
