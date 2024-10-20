package com.hmdp.utils;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
/**
 * @author Shane
 * @create 2024/10/7 - 16:03
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 判断ThreadLocal中是否有用户
        // 没有用户就拦截请求
        if(UserHolder.getUser() == null){
            response.setStatus(401);
            return false;
        }

        // 有用户就放行
        return true;
    }
}
