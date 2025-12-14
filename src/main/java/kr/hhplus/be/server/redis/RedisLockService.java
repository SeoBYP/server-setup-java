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

    public String tryLock(String key, long waitMillis, long leaseMillis)
    {
        String token = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + waitMillis;

        while(System.currentTimeMillis() < deadline)
        {
            Boolean acquied = redisTemplate.opsForValue()
                    .setIfAbsent(key, token, Duration.ofMillis(leaseMillis));

            if(Boolean.TRUE.equals(acquied))
            {
                // 락 획득 성공
                return token;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        return null;// wait 시간 내 실패
    }

    public boolean unlock(String key, String token)
    {
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
