package kr.hhplus.be.server.coupon;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class CouponService {
    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Transactional
    public UserCoupon claimCoupon(Long userId, Long couponId){
        var coupon = couponRepository.findById(couponId).get();

        if (userCouponRepository.existsByUserIdAndCouponIdAndCouponStatus(userId, couponId, CouponStatus.CLAIMED)) {
            throw new CouponAlreadyClaimedException();
        }

        //만료 시간 및 유효 기간 체크 (도메인 로직 호출)
        coupon.validateClaimable();
        UserCoupon newUserCoupon = new UserCoupon(userId, coupon.getCouponId(), CouponStatus.CLAIMED);
        return userCouponRepository.save(newUserCoupon);
    }

    public List<UserCoupon> getUserCoupons(Long userId) {
        return userCouponRepository.findAllByUserId(userId);
    }

    public UserCoupon useCoupon(Long userCouponId)
    {
        var userCoupon = userCouponRepository.findById(userCouponId).get();
        userCoupon.use();
        return userCouponRepository.save(userCoupon);
    }
}
