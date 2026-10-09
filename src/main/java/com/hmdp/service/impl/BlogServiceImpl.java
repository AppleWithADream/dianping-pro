package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import cn.hutool.core.bean.BeanUtil;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 探店笔记服务实现类：负责笔记的发布、点赞、点赞列表、好友 Feed 流。
 * 点赞用 Redis ZSet（成员=用户id，分数=点赞时间），Feed 流推送给粉丝。
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
    public Result queryHotBlog(Integer current) {
        // 1.按点赞量倒序分页查询当前页笔记
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 2.取出当前页数据
        List<Blog> records = page.getRecords();
        // 3.逐条补充作者信息与当前用户的点赞状态
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1.按 id 查笔记
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        // 2.补充作者信息与点赞状态
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        // 1.获取登录用户（/blog/hot 在白名单内、未登录也能访问，未登录直接跳过点赞状态判断，避免 NPE）
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        // 2.查询当前用户是否点赞过该笔记（ZSet 的 score 非空即已点赞）
        Long userId = user.getId();
        String key = "blog:liked:" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        // 0.登录校验
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null) {
            return Result.fail("请先登录");
        }
        // 1.获取当前用户，定位点赞 ZSet
        Long userId = userDTO.getId();
        String key = "blog:liked:" + id;
        // 2.查 ZSet 中是否有当前用户，判断点赞状态
        Double scan = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (scan == null) {
            // 2.1 未点赞 → 数据库点赞数 +1，再把用户加入 ZSet（分数=当前时间，用于排序）
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 2.2 已点赞 → 数据库点赞数 -1，并把用户从 ZSet 移除
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = "blog:liked:" + id;
        // 1.取点赞时间最早的前 5 个点赞用户（ZSet 按 score 升序）
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok();
        }
        // 2.id 列表拼接成 IN 查询条件，并按 ZSet 顺序（FIELD 函数）保持点赞先后
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 3.根据 id 批量查用户并转成 UserDTO 返回
        List<UserDTO> userDTOs = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOs);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1.设置作者为当前登录用户并保存笔记
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        save(blog);
        // 2.找出当前用户的所有粉丝，把新笔记推送到每个粉丝的 Feed 收件箱
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            String key = "feed:" + userId;
            // 成员=笔记id，分数=发布时间（时间戳）
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogByFollow(Long max, Integer offset) {
        // 1.定位当前用户的 Feed 收件箱
        Long userId = UserHolder.getUser().getId();
        String key = "feed:" + userId;
        // 2.滚动查询：score <= max，跳过 offset 条，取 2 条（实际一页 2 条由前端控制）
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 3.遍历结果：收集笔记 id；同时计算本次最小时间 minTime 与同时间个数 offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                // 与最小时间相同，偏移量累加
                os++;
            } else {
                // 出现更小的时间，重置 minTime 与 offset
                minTime = time;
                os = 1;
            }
        }
        // 4.按 id 批量查笔记，并保持 Feed 流顺序
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        // 5.逐条补充作者信息与点赞状态
        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }
        // 6.封装成滚动分页结果返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }

    // 给笔记补充作者昵称与头像
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
