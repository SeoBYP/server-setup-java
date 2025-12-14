package kr.hhplus.be.server.product.popularProduct;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class PopularProductCache {

    private static final String KEY = "cache:products:top-selling:v1";
    private static final Duration TTL = Duration.ofSeconds(60);

    private final ObjectMapper om = new ObjectMapper();

    @Autowired
    private StringRedisTemplate redis;

    public List<Long> getCachedIds() {
        String raw = redis.opsForValue().get(KEY);
        if (raw == null) return null;
        try {
            return om.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {});
        } catch (Exception e) {
            return null; // 깨진 캐시는 미스로 처리
        }
    }

    public void setIds(List<Long> ids) {
        try {
            redis.opsForValue().set(KEY, om.writeValueAsString(ids), TTL);
        } catch (Exception ignore) {}
    }
}
