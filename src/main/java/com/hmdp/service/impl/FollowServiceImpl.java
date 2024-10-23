package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    @Override
    public Result subscribeOrUnsubscribe(Long followUserId, boolean isSubscribe) {
        Long userId = UserHolder.getUser().getId();
        String key = "subscriptions:" + userId;
        // 1. 关注
        if(isSubscribe){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            // 1.1. 关注成功后, 把关注的用户存进redis, 方便后续查共同关注
            if(isSubscribe){
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else{
            // 2. 取消关注
            boolean isSuccess = remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, userId)
                    .eq(Follow::getFollowUserId, followUserId));
            // 2.1. 取消关注后, 把取关的用户从redis中移除
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        // 3. 返回
        return Result.ok();
    }

    @Override
    public Result isSubscribed(Long followUserId) {
        int count = count(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, UserHolder.getUser().getId())
                .eq(Follow::getFollowUserId, followUserId));
        return Result.ok(count > 0); // 关注了就会返回true, 反之返回false
    }

    @Override
    public Result commonFriends(Long followUserId) {
        String keyMe = "subscriptions:" + UserHolder.getUser().getId();
        String keyOther = "subscriptions:" + followUserId;
        // 1. 获取交集id
        Set<String> intersected = stringRedisTemplate.opsForSet().intersect(keyMe, keyOther);
        if(intersected == null || intersected.isEmpty()){
            // 1.1. 无交集,返回空集合即可
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersected.stream().map(Long::valueOf).collect(Collectors.toList());
        // 2. 查询交际ids对应的所有用户
        List<User> users = userService.listByIds(ids);
        List<UserDTO> result = users.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(result);
    }
}
