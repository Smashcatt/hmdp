package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IFollowService extends IService<Follow> {

    Result subscribeOrUnsubscribe(Long followUserId, boolean isSubscribe);

    Result isSubscribed(Long followUserId);

    Result commonFriends(Long followUserId);
}
