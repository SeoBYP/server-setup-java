package kr.hhplus.be.server.coupon;

import kr.hhplus.be.server.redis.RedisLockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CouponFacade {

    private static final long LOCK_WAIT_MS = 3000;
    // 기존 5초는 부하 환경에서 매우 짧습니다. 최소 30~60초 권장.
    private static final long LOCK_LEASE_MS = 60000;

    @Autowired
    private RedisLockService redisLockService;

    @Autowired
    private CouponService couponService;

    /**
     * 선착순 발급.
     *
     * 이전에는 여기서 `lock:coupon:claim:{couponId}:user:{userId}` 분산락을 잡았다.
     * 발급 요청의 Kafka 파티션 키를 userId로 바꾼 뒤로 이 락은 중복이다.
     *
     * 동일 사용자의 요청은 항상 같은 파티션에 들어가고, 한 파티션은 컨슈머 그룹 내에서
     * 스레드 하나만 소비하므로 사용자 단위 처리는 이미 직렬화된다.
     * 그 위에 선착순 판정의 원자성은 Redis Lua(SISMEMBER + DECR + SADD)가,
     * 중복 발급 차단은 user_coupons의 UNIQUE 제약이 각각 보장한다.
     *
     * 락을 유지하면 요청마다 Redis 왕복이 2회(tryLock/unlock) 더 발생하고,
     * 경합 시 20ms 폴링 스핀까지 붙는다. CPU 포화 구간에서 그대로 지연으로 나타난다.
     *
     * ⚠️ 파티션 키를 userId 외의 값으로 되돌린다면 이 락을 반드시 복구해야 한다.
     */
    public UserCoupon claimCoupon(Long userId, Long couponId, String requestId) {
        return couponService.claimCouponTx(userId, couponId, requestId);
    }

    public UserCoupon useCoupon(Long userCouponId) {
        String key = "lock:userCoupon:use:" + userCouponId;

        String token = redisLockService.tryLock(key, LOCK_WAIT_MS, LOCK_LEASE_MS);
        if (token == null) throw new IllegalStateException("LOCK_ACQUIRE_FAILED");

        try {
            return couponService.useCouponTx(userCouponId);
        } finally {
            redisLockService.unlock(key, token);
        }
    }
}
