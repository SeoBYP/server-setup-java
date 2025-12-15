package kr.hhplus.be.server.coupon;

import kr.hhplus.be.server.coupon.exception.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CouponService {
    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Transactional
    public UserCoupon claimCouponTx(Long userId, Long couponId){
        // 1. **PESSIMISTIC_WRITE 락**을 걸고 Coupon 엔티티 조회
        //    -> 이 시점에 다른 트랜잭션은 해당 쿠폰에 접근 불가
        var coupon = couponRepository.findCouponWithPessimisticLock(couponId)
                .orElseThrow(CouponNotFoundException::new);

        // 2. 중복 발급 체크
        if (userCouponRepository.existsByUserIdAndCouponIdAndCouponStatus(userId, couponId, CouponStatus.CLAIMED)) {
            throw new CouponAlreadyClaimedException();
        }

        // 3. 재고 및 기간 체크 (도메인 로직 호출)
        coupon.validateClaimable(); // 기존 로직

        try
        {
            // 4. UserCoupon 생성 및 저장
            UserCoupon newUserCoupon = new UserCoupon(userId, coupon.getCouponId(), CouponStatus.CLAIMED);
            return userCouponRepository.save(newUserCoupon);
        }catch (DataIntegrityViolationException e) {
            // 여기서 걸리는 건 결국 "이 coupon_id로 이미 누가 저장함"
            throw new CouponAlreadyClaimedException("GLOBAL_ALREADY_CLAIMED");
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
