package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.TokenRefreshInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * @author Shane
 * @create 2024/10/7 - 16:11
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Resource
    private LoginInterceptor loginInterceptor; // ⾃定义的登录拦截器对象
    @Resource
    private TokenRefreshInterceptor tokenRefreshInterceptor; // 自定义的token刷新拦截器对象

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor) // 注册⾃定义的登录拦截器对象
                .excludePathPatterns(
                        "/user/code", // 放行请求验证码的请求
                        "/user/login", // 放行用户登录的请求
                        "/blog/hot", // 放行查询热点博客的请求,因为不必要用户信息
                        "/shop/**", // 放行店铺相关的请求,因为是不是用户都应该能查店铺信息
                        "/shop-type/**", // 放行查询店铺类型的请求
                        "/voucher/**", // 放行优惠券相关的请求
                        "/upload/**" /// 放行上传信息相关的请求,方便后续测试
                ).order(1);

        // 注册自定义的token刷新拦截器对象,先执行
        registry.addInterceptor(tokenRefreshInterceptor).order(0);
    }
}
