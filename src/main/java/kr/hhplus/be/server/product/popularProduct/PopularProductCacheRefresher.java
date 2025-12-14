package kr.hhplus.be.server.product.popularProduct;

import kr.hhplus.be.server.redis.RedisLockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PopularProductCacheRefresher {

    @Autowired
    private RedisLockService redisLockService;

    @Autowired
    private PopularProductRepository popularProductRepository;

    @Autowired
    private PopularProductCache topSellingProductCache;

    @Scheduled(fixedDelay = 60_000) // 60초마다 갱신
    public void refresh() {
        String lockKey = "lock:cache:top-selling:v1";
        String token = redisLockService.tryLock(lockKey, 1000, 10_000);
        if (token == null) return;

        try {
            List<Long> topIds = popularProductRepository.findTop5ProductIds();

            topSellingProductCache.setIds(topIds == null ? List.of() : topIds);
        } finally {
            redisLockService.unlock(lockKey, token);
        }
    }
}
