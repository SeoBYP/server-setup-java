package kr.hhplus.be.server.product.popularProduct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class PopularProductRankingRedis {

    // 전체 기간 누적 Top-selling 랭킹 키
    private static final String KEY = "rank:product:top-selling:all:v1";

    @Autowired
    private StringRedisTemplate redis;

    public void increaseSales(Long productId, long quantity) {
        // ZINCRBY KEY quantity member
        redis.opsForZSet().incrementScore(KEY, productId.toString(), quantity);
    }

    public List<Long> getTopIds(int topN) {
        if (topN <= 0) return List.of();

        Set<String> raw = redis.opsForZSet().reverseRange(KEY, 0, topN - 1);
        if (raw == null || raw.isEmpty()) return List.of();

        return raw.stream()
                .map(Long::valueOf)
                .toList();
    }
}
