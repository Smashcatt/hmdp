package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Override
    public Result subscribeOrUnsubscribe(Long followUserId, boolean isSubscribe) {
        Long userId = UserHolder.getUser().getId();
        // 1. 关注
        if(isSubscribe){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            save(follow);
        }else{
            // 2. 取消关注
            remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, userId)
                    .eq(Follow::getFollowUserId, followUserId));
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
}
