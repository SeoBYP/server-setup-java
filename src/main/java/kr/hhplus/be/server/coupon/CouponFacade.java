package kr.hhplus.be.server.coupon;

import kr.hhplus.be.server.redis.RedisLockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CouponFacade {

    @Autowired
    private RedisLockService redisLockService;

    @Autowired
    private CouponService couponService;

    public UserCoupon claimCoupon(Long userId, Long couponId)
    {
        String key = "lock:coupon:claim:" + couponId; // 핵심 키

        String token = redisLockService.tryLock(key, 3000, 5000);
        if (token == null) throw new IllegalStateException("LOCK_ACQUIRE_FAILED");

        try {
            return couponService.claimCouponTx(userId, couponId);
        } finally {
            redisLockService.unlock(key, token);
        }
    }

    public UserCoupon useCoupon(Long userCouponId)
    {
        String key = "lock:userCoupon:use:" + userCouponId;

        String token = redisLockService.tryLock(key, 3000, 5000);
        if (token == null) throw new IllegalStateException("LOCK_ACQUIRE_FAILED");

        try {
            return couponService.useCouponTx(userCouponId);
        } finally {
            redisLockService.unlock(key, token);
        }
    }
}
