package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.BeanUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.ScrollResult;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogOfFollowing(Long maxScore, Integer offset) {
        String key = FEED_KEY + UserHolder.getUser().getId();

        // 1. 获取本用户的收件箱中范围内的, 所有博客id及其score
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, maxScore, offset, 2);

        // 2. 解析数据，获取范围内的所有博客id，获取最小score及其count
        if(tuples == null || tuples.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> blogIdList = new ArrayList<>(tuples.size());
        long minScore = 0;
        int count = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            blogIdList.add(Long.valueOf(tuple.getValue()));

            long score = tuple.getScore().longValue();
            if(score == minScore){
                count++;
            }else{
                minScore = score;
                count = 1;
            }
        }

        // 3. 根据id查询博客，同时还要保证结果的顺序
        String idListStr = StrUtil.join(",", blogIdList);
        List<Blog> blogs = query()
                .in("id", blogIdList).last("ORDER BY FIELD(id, " + idListStr + ")")
                .list();
        // 封装博客的用户和是否被点赞的信息
        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }

        // 4. 封装并返回
        ScrollResult scrollResult = new ScrollResult(blogs, minScore, count);
        return Result.ok(scrollResult);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1. 获取登录用户并封装
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2. 保存博客进数据库
        boolean isSuccess = save(blog);
        if(isSuccess){
            // 3. 将博客数据推送进粉丝的收件箱
            // 3.1. 获取所有粉丝的id
            List<Follow> follows = followService.list(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getFollowUserId, user.getId()));
            for (Follow follow : follows) {
                // 3.2. 获取粉丝id
                Long fansId = follow.getUserId();
                // 3.3. 推送
                String key = FEED_KEY + fansId;
                stringRedisTemplate.opsForZSet()
                        .add(key, blog.getId().toString(), System.currentTimeMillis());
            }
        }
        // 4. 返回博客id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogByUserId(Long id, Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .eq("user_id", id)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @Override
    public Result likeLeaderboard(Long id) {
        // 1. 在Redis中查询给该博客点赞的所有用户的id
        String key = BLOG_LIKED_KEY + id;
        Set<String> members = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(members == null || members.isEmpty()){
            return Result.ok();
        }

        // 2. 根据用户id去数据库查询用户的具体信息
        Set<Long> userIds = members.stream().map(Long::valueOf).collect(Collectors.toSet());
        // 比如输入的id是5和1, 但输出的结果却按照1和5展示, 所以我们要修正这个问题
        // 利用这个sql语句：WHERE id in (5,1) ORDER BY FIELD("id",5,1)
        String idStr = StrUtil.join(",", userIds);
        List<User> users = userService.query()
                .in("id", userIds).last("ORDER BY FIELD(id, " + idStr + ")")
                .list();

        // 3. 将User类型转换成UserDTO类型,并放装进结果List集合里
        List<UserDTO> result = users.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        // 4. 返回
        return Result.ok(result);
    }

    @Override
    public Result queryDetailedBlog(Long id) {
        // 1. 查询博客信息
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在");
        }
        // 2. 封装博客的所属用户信息
        queryBlogUser(blog);
        // 3. 查询博客是否被点赞过
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 1. 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 2. 获取当前页数据
        List<Blog> records = page.getRecords();
        // 3. 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });

        return Result.ok(records);
    }

    private void isBlogLiked(Blog blog) {
        // 1. 用户还未登录, 就啥都不做
        UserDTO user = UserHolder.getUser();
        if(user == null) return;
        // 2. 查询缓存中该博客是否被点赞过
        String key = BLOG_LIKED_KEY + blog.getId();
        String member = String.valueOf(user.getId());
        Double score = stringRedisTemplate.opsForZSet().score(key, member);
        // 3. 若点赞了则score就有值, isLike字段设置为true,
        // 没点赞score==null, isLike字段设置为false
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        String key = BLOG_LIKED_KEY + id;
        String member = String.valueOf(UserHolder.getUser().getId());

        // 1. 获取博客的点赞信息
        Double score = stringRedisTemplate.opsForZSet().score(key, member);
        // 2. 用户已经给博客点过赞了, 则取消点赞
        if(score != null){
            // 2.1. 更新数据库点赞信息 -1
            LambdaUpdateWrapper<Blog> luw = new LambdaUpdateWrapper<>();
            luw.setSql("liked = liked - 1")
                    .eq(Blog::getId, id);
            if(update(luw)){ // 数据库更新成功才执行下面操作
                // 2.2. 将用户从Redis集合中移除
                stringRedisTemplate.opsForZSet().remove(key, member);
            }
        }else{ // 3. 用户没点过赞, 缓存点赞信息
            // 3.1. 新增数据库点赞信息
            LambdaUpdateWrapper<Blog> luw = new LambdaUpdateWrapper<>();
            luw.setSql("liked = liked + 1")
                    .eq(Blog::getId, id);
            if(update(luw)){ // 数据库更新成功才执行下面操作
                // 3.2. 更新缓存点赞信息
                stringRedisTemplate.opsForZSet().add(key, member, System.currentTimeMillis());
            }
        }

        return Result.ok();
    }

    private void queryBlogUser(Blog blog) {
        User user = userService.getById(blog.getUserId());
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
