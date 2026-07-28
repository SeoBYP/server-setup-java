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
import java.util.Optional;

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
            if remain == nil then
              return -2 -- NOT_INITIALIZED (방어)
            end
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void compensateIncrementRemaining(Long couponId) {
        couponRepository.incrementRemaining(couponId);
    }

    @Transactional
    public UserCoupon claimCouponByMessage(String requestId, Long userId, Long couponId) {
        var idempotent = userCouponRepository.findByRequestId(requestId);
        if (idempotent.isPresent()) return idempotent.get();

        var coupon = couponRepository.findById(couponId)
                .orElseThrow(CouponNotFoundException::new);

        coupon.validateClaimable();

        var existing = userCouponRepository
                .findByUserIdAndCouponIdAndCouponStatus(userId, couponId, CouponStatus.CLAIMED);
        if (existing.isPresent()) {
            return existing.get();
        }

        int updated = couponRepository.decrementRemainingIfAvailable(couponId);
        if (updated == 0) {
            throw new CouponSoldOutException();
        }

        try {
            return tryInsertUserCoupon(requestId, userId, couponId);
        } catch (DataIntegrityViolationException e) {
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

        // ✅ Redis remain 초기값을 "1"로 고정하지 말고, DB의 남은 수량(또는 총 수량)으로 맞춘다.
        // - 여기서는 Coupon 엔티티의 remainingQuantity를 사용한다고 가정합니다.
        initRemainIfAbsent(couponId, coupon.getRemainingQuantity());

        String remainKey = "coupon:" + couponId + ":remain";
        String issuedKey = "coupon:" + couponId + ":issued";

        Long r = redisTemplate.execute(
                claimScript,
                List.of(remainKey, issuedKey),
                userId.toString()
        );

        if (r == null) throw new IllegalStateException("REDIS_EXECUTE_FAILED");

        if (r == -1L) throw new CouponAlreadyClaimedException();
        // ✅ r == 0 은 SOLD_OUT 이므로 "이미 사용됨"이 아니라 "소진"으로 처리
        if (r == 0L) throw new CouponSoldOutException();
        // ✅ remainKey가 초기화 안 된 경우 방어(이 케이스가 뜨면 init 로직/키 삭제 여부를 점검)
        if (r == -2L) throw new IllegalStateException("COUPON_REDIS_NOT_INITIALIZED");

        try {
            UserCoupon newUserCoupon = new UserCoupon(userId, couponId, requestId, CouponStatus.CLAIMED);
            return userCouponRepository.save(newUserCoupon);
        } catch (DataIntegrityViolationException e) {
            // ✅ DB UNIQUE 충돌이면 Redis에서 선점한 발급 흔적을 롤백
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

    /**
     * 발급 응답 조립용 단건 조회.
     * 기존에는 사용자의 쿠폰 목록 전체를 읽어 스트림으로 하나를 골랐다.
     * 발급이 누적될수록 응답당 읽는 행 수가 늘어나므로 PK 조회로 대체한다.
     */
    public Optional<UserCoupon> getUserCoupon(Long userCouponId) {
        return userCouponRepository.findById(userCouponId);
    }
}