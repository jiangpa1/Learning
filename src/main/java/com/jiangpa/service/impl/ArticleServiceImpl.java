package com.jiangpa.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiangpa.common.CacheKeys;
import com.jiangpa.common.PageResult;
import com.jiangpa.dto.ArticleDTO;
import com.jiangpa.exception.BusinessException;
import com.jiangpa.mapper.ArticleMapper;
import com.jiangpa.mapper.UserMapper;
import com.jiangpa.pojo.Article;
import com.jiangpa.pojo.User;
import com.jiangpa.service.ArticleService;
import com.jiangpa.vo.ArticleDetailVO;
import com.jiangpa.vo.ArticleListVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ArticleServiceImpl implements ArticleService {
    private static final long DETAIL_TTL_SECONDS = 30 * 60;
    private static final int  TTL_JITTER_SECONDS = 300;
    private static final long NULL_TTL_MINUTES   = 2;
    private static final long LOCK_TTL_SECONDS   = 10;
    private static final int  MAX_RETRY          = 1;
    private static final long RETRY_WAIT_MILLIS  = 100;

    private final ArticleMapper articleMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /** 解锁脚本：只有 value 还是我的 token 才删，比对+删除在 Redis 内原子完成 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] " +
                    "then " +
                    "return redis.call('del', KEYS[1]) " +
                    "else " +
                    "return 0 " +
                    "end",
            Long.class);

    public ArticleServiceImpl(ArticleMapper articleMapper, UserMapper userMapper, StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.articleMapper = articleMapper;
        this.userMapper = userMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public ArticleDetailVO selectArticleById(Long id){
        return selectArticleById(id, 0);
    }

    private ArticleDetailVO selectArticleById(Long id, int retryCount){
        //引入redis后
        String cached = cacheGetDetail(id);

        if(cached != null) {
            if (CacheKeys.NULL_SENTINEL.equals(cached)) {
                throw new BusinessException(404, "文章不存在！");
            }

            ArticleDetailVO articleDetailVO = parseCachedDetail(cached, id);
            if (articleDetailVO != null) {
                articleDetailVO.setViewCount(cacheNextViewCount(id, null));
                return articleDetailVO;
            }

        }

        String token = cacheTryLock(id);
        if (token != null) {
            try {
                // 抢到锁 → 查 DB、回填缓存
                return fetchFromDbAndCache(id);
            } finally {
                cacheUnlock(id, token);
            }
        } else {
            // 没抢到 → 短暂等待后重试读缓存
            if (retryCount < MAX_RETRY) {
                sleepQuietly(RETRY_WAIT_MILLIS);
                return selectArticleById(id, retryCount + 1);
            }
            return fetchFromDbAndCache(id);
        }
    }

    @Override
    public PageResult<?> selectArticlesList(Integer pageNum, Integer pageSize) {
        pageSize = Math.min(pageSize, 50);
        Page<Article> page = new Page<>(pageNum, pageSize);



        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(Article::getId, Article::getTitle, Article::getSummary,
                        Article::getUserId, Article::getViewCount, Article::getCreateTime)
                .orderByDesc(Article::getCreateTime);

        IPage<Article> result = articleMapper.selectPage(page, wrapper);


        List<Long> userIds = result.getRecords().stream()
                .map(Article::getUserId)
                .distinct()
                .toList();

        Map<Long, String> nicknameMap = userIds.isEmpty()
                ? new HashMap<>()
                : userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId,
                        u -> u.getNickname() != null ? u.getNickname() : "默认昵称",
                        (oldVal, newVal) -> oldVal));


        List<ArticleListVO> voList = result.getRecords().stream()
                .map(article -> {
                    ArticleListVO vo = new ArticleListVO();
                    BeanUtils.copyProperties(article, vo);
                    vo.setAuthorNickname(nicknameMap.getOrDefault(article.getUserId(), "未知作者"));
                    return vo;
                })
                .toList();


        PageResult<ArticleListVO> pageResult = new PageResult<>();
        pageResult.setTotal(result.getTotal());
        pageResult.setPageNum(result.getCurrent());
        pageResult.setPageSize(result.getSize());
        pageResult.setPages(result.getPages());
        pageResult.setRecords(voList);

        return pageResult;
    }

    @Override
    public Long addArticle(ArticleDTO articleDTO, Long userId) {
        Article article = new Article();
        BeanUtils.copyProperties(articleDTO, article);
        article.setUserId(userId);
        LocalDateTime now = LocalDateTime.now();
        article.setCreateTime(now);
        article.setUpdateTime(now);

        String summary = getSummary(articleDTO.getContent());

        article.setSummary(summary);
        articleMapper.insert(article);

        return article.getId();
    }

    @Override
    public void updateArticle(ArticleDTO articleDTO, Long id, Long userId) {
        Article article = articleMapper.selectById(id);

        if (article == null) {
            throw new BusinessException(404, "文章不存在");
        }

        if (!Objects.equals(article.getUserId(), userId)) {
            throw new BusinessException(403, "无权操作他人文章");
        }

        LambdaUpdateWrapper<Article> wrapper = new LambdaUpdateWrapper<>();

        String summary = getSummary(articleDTO.getContent());
        wrapper.eq(Article::getId, id)
                .set(Article::getTitle, articleDTO.getTitle())
                .set(Article::getContent, articleDTO.getContent())
                .set(Article::getSummary, summary)
                .set(Article::getCategoryId, articleDTO.getCategoryId())
                .set(Article::getUpdateTime, LocalDateTime.now());

        articleMapper.update(null, wrapper);
        cacheEvict(CacheKeys.articleDetail(id));
    }

    @Override
    public void deleteArticle(Long id, Long userId) {
        Article article = articleMapper.selectById(id);

        if (article == null) {
            throw new BusinessException(404, "文章不存在");
        }

        if (!Objects.equals(article.getUserId(), userId)) {
            throw new BusinessException(403, "无权操作他人文章");
        }

        articleMapper.deleteById(id);
        cacheEvict(CacheKeys.articleDetail(id), CacheKeys.articleViews(id), CacheKeys.articleLock(id));
    }

    /** 列表中文章摘要生成 */
    private String getSummary(String content) {
        String plain = content.trim().replaceAll("\\s+", " ");
        if (plain.length() <= 100) {
            return plain;
        }
        return plain.substring(0, 100) + "...";
    }

    /** 查DB，回填缓存 */
    private ArticleDetailVO fetchFromDbAndCache(Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            cacheMarkNotExist(id);
            throw new BusinessException(404, "文章不存在！");
        }

        ArticleDetailVO articleDetailVO = new ArticleDetailVO();
        User author = userMapper.selectById(article.getUserId());

        BeanUtils.copyProperties(article, articleDetailVO);
        articleDetailVO.setAuthorNickname(author == null ? null : author.getNickname());

        Long views = cacheNextViewCount(id, article.getViewCount());

        articleDetailVO.setViewCount(null);
        cachePutDetail(id, articleDetailVO);

        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, id)
                .set(Article::getViewCount, views));

        articleDetailVO.setViewCount(views);
        return articleDetailVO;
    }

    /** 睡眠工具 */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();      // 恢复中断标志
            log.warn("等待被中断", e);
        }
    }

    /** 读内容缓存。未命中或 Redis 异常都返回 null，调用方按未命中处理 */
    private String cacheGetDetail(Long id) {
        String key = CacheKeys.articleDetail(id);
        try {
            return stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("读缓存失败，降级为查 DB，key={}", key, e);
            return null;
        }
    }

    /** 解析缓存的 JSON。失败时清掉坏数据并返回 null，让流程落到回源分支。 */
    private ArticleDetailVO parseCachedDetail(String json, Long id) {
        try {
            return objectMapper.readValue(json, ArticleDetailVO.class);
        } catch (JsonProcessingException e) {
            log.warn("缓存反序列化失败，按未命中处理，key={}", CacheKeys.articleDetail(id), e);
            cacheEvict(CacheKeys.articleDetail(id));
            return null;
        }
    }

    /** 回填内容缓存。失败不影响本次返回（数据已经从 DB 拿到了）。
     *  注意：调用方需先把 viewCount 置 null，浏览量不进缓存内容。 */
    private void cachePutDetail(Long id, ArticleDetailVO vo) {
        String key = CacheKeys.articleDetail(id);
        try {
            String json = objectMapper.writeValueAsString(vo);
            long ttl = DETAIL_TTL_SECONDS + ThreadLocalRandom.current().nextInt(TTL_JITTER_SECONDS);
            stringRedisTemplate.opsForValue().set(key, json, ttl, TimeUnit.SECONDS);
        } catch (Exception e) {                    // 一次覆盖序列化失败 + 连接失败
            log.warn("回填缓存失败，不影响本次返回，key={}", key, e);
        }
    }

    /** 写空值哨兵防穿透。失败只记日志，功能正常（只是防穿透暂时失效） */
    private void cacheMarkNotExist(Long id) {
        String key = CacheKeys.articleDetail(id);
        try {
            stringRedisTemplate.opsForValue()
                    .set(key, CacheKeys.NULL_SENTINEL, NULL_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("写入空值哨兵失败，防穿透暂时失效，key={}", key, e);
        }
    }

    /** 清除若干缓存 key。失败只记日志（最坏情况是脏到 TTL 过期）。 */
    private void cacheEvict(String... keys) {
        try {
            for (String key : keys) {
                stringRedisTemplate.delete(key);
            }
        } catch (Exception e) {
            log.warn("清除缓存失败，已忽略，keys={}", Arrays.toString(keys), e);
        }
    }

    /**
     * 让浏览量 +1。
     * @param dbValue 数据库里的当前值：用于首次播种，以及 Redis 不可用时兜底
     * @return 自增后的值；Redis 不可用时返回 dbValue
     */
    private Long cacheNextViewCount(Long id, Long dbValue) {
        String key = CacheKeys.articleViews(id);
        try {
            // 首次播种：只在 key 不存在时写入，避免用 DB 旧值覆盖 Redis 已累积的计数
            stringRedisTemplate.opsForValue().setIfAbsent(key, String.valueOf(dbValue == null ? 0L : dbValue));
            Long v = stringRedisTemplate.opsForValue().increment(key);
            return v != null ? v : dbValue;
        } catch (Exception e) {
            log.warn("浏览量自增失败，退回数据库值，key={}", key, e);
            return dbValue;
        }
    }

    /** 抢锁。抢到返回本次的唯一 token；已被占用或 Redis 异常都返回 null（调用方按"没抢到"处理）。 */
    private String cacheTryLock(Long id) {
        String key = CacheKeys.articleLock(id);
        String token = UUID.randomUUID().toString();
        try {
            Boolean ok = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, token, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(ok) ? token : null;
        } catch (Exception e) {
            log.warn("抢锁失败（Redis 不可用），直接查 DB，key={}", key, e);
            return null;
        }
    }

    /** 释放锁。失败只记日志 —— 锁有 TTL，会自然过期。 */
    private void cacheUnlock(Long id, String token) {
        String key = CacheKeys.articleLock(id);
        try {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
        } catch (Exception e) {
            log.warn("释放锁失败，将等待其自然过期，key={}", key, e);
        }
    }
}
