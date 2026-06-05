package com.ieltsstudio.infra;

import java.time.Duration;
import java.util.Map;

public class RedisOpsNoopImpl implements RedisOps {

    @Override
    public Object get(String key) {
        return null;
    }

    @Override
    public void set(String key, Object val, Duration ttl) {
    }

    @Override
    public void hsetAll(String key, Map<String, Object> map) {
    }

    @Override
    public Map<String, Object> hgetAll(String key) {
        return Map.of();
    }

    @Override
    public void expire(String key, Duration ttl) {
    }

    @Override
    public Boolean sismember(String key, String member) {
        return false;
    }

    @Override
    public Long sadd(String key, String... members) {
        return 0L;
    }

    @Override
    public Boolean tryLock(String key, Duration ttl) {
        return false;
    }

    @Override
    public void del(String key) {
    }
}
