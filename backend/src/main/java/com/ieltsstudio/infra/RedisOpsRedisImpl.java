package com.ieltsstudio.infra;

import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

public class RedisOpsRedisImpl implements RedisOps {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisOpsRedisImpl(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    @Override
    public void set(String key, Object val, Duration ttl) {
        if (ttl != null) {
            redisTemplate.opsForValue().set(key, val, ttl);
        } else {
            redisTemplate.opsForValue().set(key, val);
        }
    }

    @Override
    public void hsetAll(String key, Map<String, Object> map) {
        if (map != null) {
            redisTemplate.opsForHash().putAll(key, map);
        }
    }

    @Override
    public Map<String, Object> hgetAll(String key) {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(key);
        if (raw == null || raw.isEmpty()) return Map.of();
        return raw.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().toString(), Map.Entry::getValue));
    }

    @Override
    public void expire(String key, Duration ttl) {
        if (ttl != null) {
            redisTemplate.expire(key, ttl);
        }
    }

    @Override
    public Boolean sismember(String key, String member) {
        return redisTemplate.opsForSet().isMember(key, member);
    }

    @Override
    public Long sadd(String key, String... members) {
        if (members == null || members.length == 0) return 0L;
        return redisTemplate.opsForSet().add(key, (Object[]) members);
    }

    @Override
    public Boolean tryLock(String key, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "1", ttl));
    }

    @Override
    public void del(String key) {
        redisTemplate.delete(key);
    }
}
