package kr.hhplus.be.server.coupon;

import kr.hhplus.be.server.coupon.exception.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
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

    @Transactional
    public UserCoupon claimCouponByMessage(String requestId, Long userId, Long couponId) {
        var coupon = couponRepository.findById(couponId)
                .orElseThrow(CouponNotFoundException::new);

        coupon.validateClaimable();

        // (선택) 빠른 중복 체크
        if (userCouponRepository.existsByUserIdAndCouponIdAndCouponStatus(userId, couponId, CouponStatus.CLAIMED)) {
            throw new CouponAlreadyClaimedException();
        }

        int updated = couponRepository.decrementRemainingIfAvailable(couponId);
        if (updated == 0) {
            throw new IllegalStateException("COUPON_SOLD_OUT");
        }

        try {
            return userCouponRepository.save(new UserCoupon(userId, couponId, requestId, CouponStatus.CLAIMED));
        } catch (DataIntegrityViolationException e) {
            // ✅ 남은 수량 원복
            couponRepository.incrementRemaining(couponId);

            // ✅ requestId 멱등(재처리) 케이스면 기존 row 반환
            return userCouponRepository.findByRequestId(requestId)
                    .orElseThrow(() -> e);
        }
    }


    @Transactional
    public UserCoupon claimCouponTx(Long userId, Long couponId, String requestId)
    {
        // 0) 기간/상태 체크는 DB에서 해도 됨 (정확)
        var coupon = couponRepository.findById(couponId)
                .orElseThrow(CouponNotFoundException::new);
        coupon.validateClaimable();

        // 쿠폰은 항상 1개로만 체크(여러 쿠폰은 없음)
        initRemainIfAbsent(couponId, 1);

        // 1) Redis에서 선착순 확정 (원자)
        String remainKey = "coupon:" + couponId + ":remain";
        String issuedKey = "coupon:" + couponId + ":issued";

        Long r = redisTemplate.execute(
                claimScript,
                List.of(remainKey, issuedKey),
                userId.toString()
        );

        if (r == -1L) throw new CouponAlreadyClaimedException();
        if (r == 0L)  throw new CouponAlreadyUsedException();

        // 핵심: Redis remain이 없으면 초기화 => 쿠폰은 무조건 1개만 있음
        initRemainIfAbsent(couponId, 1); // 네 엔티티 필드명에 맞게 변경

        // 2) Redis 성공이면 DB에 기록
        try {
            UserCoupon newUserCoupon = new UserCoupon(userId, couponId, requestId,CouponStatus.CLAIMED);
            return userCouponRepository.save(newUserCoupon);
        } catch (DataIntegrityViolationException e) {
            // 3) DB 실패하면 Redis 보상(이번 요청만)
            redisTemplate.opsForSet().remove(issuedKey, userId.toString());
            redisTemplate.opsForValue().increment(remainKey);
            throw new CouponAlreadyClaimedException("DB_CONFLICT");
        }
    }

    @Transactional
    public UserCoupon validateAndLockUserCoupon(Long userCouponId, Long expectedUserId) {
        // PESSIMISTIC_WRITE 락을 걸고 UserCoupon 조회
        // 주문 시 해당 쿠폰의 동시 사용을 방지합니다.
        UserCoupon userCoupon = userCouponRepository.findForUpdate(userCouponId)
                .orElseThrow(UserCouponNotFoundException::new);

        // 1. 소유자 일치 확인
        if (!userCoupon.getUserId().equals(expectedUserId)) {
            throw new CouponOwnerMismatchException(); // 적절한 예외 처리 필요
        }

        // 2. 사용 가능한 상태인지 확인 (CLAIMED 상태만 사용 가능)
        if (userCoupon.getCouponStatus() != CouponStatus.CLAIMED) {
            throw new CouponAlreadyUsedException(); // 이미 사용된 쿠폰
        }

        // 유효 기간 체크는 OrderService에서 Coupon 엔티티를 로드하여 진행하는 것이 일반적입니다.

        return userCoupon;
    }

    @Transactional
    public Coupon getCouponById(Long couponId) {
        return couponRepository.findById(couponId)
                .orElseThrow(CouponNotFoundException::new);
    }

    @Transactional
    public UserCoupon saveUserCoupon(UserCoupon userCoupon) {
        // OrderService 트랜잭션 내에서 use()가 호출된 객체를 최종 저장합니다.
        return userCouponRepository.save(userCoupon);
    }

    @Transactional
    public UserCoupon useCouponTx(Long userCouponId)
    {
        var userCoupon = userCouponRepository.findForUpdate(userCouponId).get();
        userCoupon.use();
        return userCouponRepository.save(userCoupon);
    }

    @Transactional
    public List<UserCoupon> getUserCoupons(Long userId) {
        return userCouponRepository.findAllByUserId(userId);
    }
}
