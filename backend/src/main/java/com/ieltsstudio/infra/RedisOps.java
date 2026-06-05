package com.ieltsstudio.infra;

import java.time.Duration;
import java.util.Map;

public interface RedisOps {

    Object get(String key);

    void set(String key, Object val, Duration ttl);

    void hsetAll(String key, Map<String, Object> map);

    Map<String, Object> hgetAll(String key);

    void expire(String key, Duration ttl);

    Boolean sismember(String key, String member);

    Long sadd(String key, String... members);

    Boolean tryLock(String key, Duration ttl);

    void del(String key);
}
