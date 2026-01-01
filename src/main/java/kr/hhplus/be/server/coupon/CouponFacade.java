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

    public UserCoupon claimCoupon(Long userId, Long couponId, String requestId) {
        String key = "lock:coupon:claim:" + couponId + ":user:" + userId;

        String token = redisLockService.tryLock(key, LOCK_WAIT_MS, LOCK_LEASE_MS);
        if (token == null) throw new IllegalStateException("LOCK_ACQUIRE_FAILED");

        try {
            return couponService.claimCouponTx(userId, couponId, requestId);
        } finally {
            redisLockService.unlock(key, token);
        }
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
