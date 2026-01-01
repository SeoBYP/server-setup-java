package kr.hhplus.be.server.redis;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class RedisLockService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * @return lock token if acquired, otherwise null
     */
    public String tryLock(String key, long waitMillis, long leaseMillis) {
        final long deadline = System.currentTimeMillis() + waitMillis;
        final String token = UUID.randomUUID().toString();
        final Duration ttl = Duration.ofMillis(leaseMillis);

        while (System.currentTimeMillis() < deadline) {
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
            if (Boolean.TRUE.equals(ok)) {
                return token;
            }
            // busy spin 방지
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * token 기반 안전 해제 (다른 소유자가 획득한 락을 지우지 않도록 Lua로 비교 후 del)
     */
    public boolean unlock(String key, String token) {
        String script =
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "  return redis.call('del', KEYS[1]) " +
                        "else " +
                        "  return 0 " +
                        "end";

        Long result = redisTemplate.execute(
                new org.springframework.data.redis.core.script.DefaultRedisScript<>(script, Long.class),
                java.util.Collections.singletonList(key),
                token
        );
        return result != null && result == 1L;
    }
}
