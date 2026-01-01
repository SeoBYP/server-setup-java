// language: java
package kr.hhplus.be.server.coupon;

import jakarta.persistence.EntityManager;
import kr.hhplus.be.server.coupon.exception.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CouponService {
    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EntityManager entityManager;

    private final DefaultRedisScript<Long> claimScript = new DefaultRedisScript<>();

    public CouponService() {
        claimScript.setResultType(Long.class);
        claimScript.setScriptText("""
            local remainKey = KEYS[1]
            local issuedKey = KEYS[2]
            local userId = ARGV[1]
            
            if redis.call('SISMEMBER', issuedKey, userId) == 1 then
              return -1
            end
            
            local remainStr = redis.call('GET', remainKey)
            local remain = tonumber(remainStr)
            if remain <= 0 then
              return 0 -- SOLD_OUT
            end
            
            redis.call('DECR', remainKey)
            redis.call('SADD', issuedKey, userId)
            return 1
        """);
    }

    private void initRemainIfAbsent(Long couponId, long totalQuantity) {
        String remainKey = "coupon:" + couponId + ":remain";
        redisTemplate.opsForValue()
                .setIfAbsent(remainKey, String.valueOf(totalQuantity));
    }

    /**
     * 보상(remaining 원복)만 별도 트랜잭션에서 처리
     * - 기존 트랜잭션은 UNIQUE 충돌 등 예외로 인해 영속성 컨텍스트가 안전하지 않을 수 있으므로 분리한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void compensateIncrementRemaining(Long couponId) {
        couponRepository.incrementRemaining(couponId);
    }

    @Transactional
    public UserCoupon claimCouponByMessage(String requestId, Long userId, Long couponId) {
        // 0) requestId 멱등 우선
        var idempotent = userCouponRepository.findByRequestId(requestId);
        if (idempotent.isPresent()) return idempotent.get();

        var coupon = couponRepository.findById(couponId)
                .orElseThrow(CouponNotFoundException::new);

        coupon.validateClaimable();

        // 1) 이미 발급된 유저면 기존 쿠폰 반환 (예외 던지지 않음)
        var existing = userCouponRepository
                .findByUserIdAndCouponIdAndCouponStatus(userId, couponId, CouponStatus.CLAIMED);
        if (existing.isPresent()) {
            return existing.get();  // ✅ 예외 대신 기존 쿠폰 반환
        }

        // 2) 재고 감소(원자)
        int updated = couponRepository.decrementRemainingIfAvailable(couponId);
        if (updated == 0) {
            throw new IllegalStateException("COUPON_SOLD_OUT");
        }

        // 3) INSERT는 REQUIRES_NEW로 격리
        try {
            return tryInsertUserCoupon(requestId, userId, couponId);
        } catch (DataIntegrityViolationException e) {
            // ✅ UNIQUE 제약 조건 위반 = 누군가 먼저 발급받음
            // 트랜잭션 격리 수준 때문에 재조회해도 안 보이므로 바로 예외 처리
            entityManager.clear();
            compensateIncrementRemaining(couponId);
            throw new CouponAlreadyClaimedException();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserCoupon tryInsertUserCoupon(String requestId, Long userId, Long couponId) {
        return userCouponRepository.save(new UserCoupon(userId, couponId, requestId, CouponStatus.CLAIMED));
    }


    @Transactional
    public UserCoupon claimCouponTx(Long userId, Long couponId, String requestId) {
        var coupon = couponRepository.findById(couponId)
                .orElseThrow(CouponNotFoundException::new);
        coupon.validateClaimable();

        initRemainIfAbsent(couponId, 1);

        String remainKey = "coupon:" + couponId + ":remain";
        String issuedKey = "coupon:" + couponId + ":issued";

        Long r = redisTemplate.execute(
                claimScript,
                List.of(remainKey, issuedKey),
                userId.toString()
        );

        if (r == -1L) throw new CouponAlreadyClaimedException();
        if (r == 0L) throw new CouponAlreadyUsedException();

        initRemainIfAbsent(couponId, 1);

        try {
            UserCoupon newUserCoupon = new UserCoupon(userId, couponId, requestId, CouponStatus.CLAIMED);
            return userCouponRepository.save(newUserCoupon);
        } catch (DataIntegrityViolationException e) {
            redisTemplate.opsForSet().remove(issuedKey, userId.toString());
            redisTemplate.opsForValue().increment(remainKey);
            throw new CouponAlreadyClaimedException("DB_CONFLICT");
        }
    }

    @Transactional
    public UserCoupon validateAndLockUserCoupon(Long userCouponId, Long expectedUserId) {
        UserCoupon userCoupon = userCouponRepository.findForUpdate(userCouponId)
                .orElseThrow(UserCouponNotFoundException::new);

        if (!userCoupon.getUserId().equals(expectedUserId)) {
            throw new CouponOwnerMismatchException();
        }

        if (userCoupon.getCouponStatus() != CouponStatus.CLAIMED) {
            throw new CouponAlreadyUsedException();
        }

        return userCoupon;
    }

    @Transactional
    public Coupon getCouponById(Long couponId) {
        return couponRepository.findById(couponId)
                .orElseThrow(CouponNotFoundException::new);
    }

    @Transactional
    public UserCoupon saveUserCoupon(UserCoupon userCoupon) {
        return userCouponRepository.save(userCoupon);
    }

    @Transactional
    public UserCoupon useCouponTx(Long userCouponId) {
        var userCoupon = userCouponRepository.findForUpdate(userCouponId).get();
        userCoupon.use();
        return userCouponRepository.save(userCoupon);
    }

    @Transactional
    public List<UserCoupon> getUserCoupons(Long userId) {
        return userCouponRepository.findAllByUserId(userId);
    }
}