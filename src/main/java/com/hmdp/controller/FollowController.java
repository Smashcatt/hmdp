package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;
    @PutMapping("/{id}/{isSubscribe}")
    public Result subscribeOrUnsubscribe(@PathVariable("id") Long followUserId,
                                 @PathVariable("isSubscribe") Boolean isSubscribe){
        return followService.subscribeOrUnsubscribe(followUserId, isSubscribe);
    }

    @GetMapping("/or/not/{id}")
    public Result isSubscribed(@PathVariable("id") Long followUserId){
        return followService.isSubscribed(followUserId);
    }

    @GetMapping("/common/{id}")
    public Result commonFriends(@PathVariable("id") Long followUserId){
        return followService.commonFriends(followUserId);
    }
}
