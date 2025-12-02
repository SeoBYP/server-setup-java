package kr.hhplus.be.server.coupon;

import kr.hhplus.be.server.coupon.DTO.ClaimRequest;
import kr.hhplus.be.server.coupon.exception.CouponAlreadyClaimedException;
import kr.hhplus.be.server.coupon.exception.CouponExpiredException;
import kr.hhplus.be.server.coupon.exception.CouponNotYetAvailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    @Autowired
    private CouponService couponService;

    // 1. 선착순 쿠폰 발급 API (POST /api/coupons/claim/{couponId})
    @PostMapping("/claim/{couponId}")
    public ResponseEntity<UserCoupon> claimCoupon(@PathVariable Long couponId, @RequestBody ClaimRequest claimRequest) {
        // 실제 운영 환경에서는 인증 시스템을 통해 userId를 가져와야 합니다.
        Long userId = claimRequest.userId();

        try {
            UserCoupon claimedCoupon = couponService.claimCoupon(userId, couponId);
            return ResponseEntity.ok(claimedCoupon);
        } catch (CouponAlreadyClaimedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build(); // 409
        } catch (CouponNotYetAvailableException | CouponExpiredException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build(); // 403
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build(); // 500
        }
    }

    // 2. 보유 쿠폰 목록 조회 API (GET /api/users/{userId}/coupons)
    @GetMapping("/users/{userId}/coupons")
    public ResponseEntity<List<UserCoupon>> getUserCoupons(@PathVariable Long userId) {
        List<UserCoupon> userCoupons = couponService.getUserCoupons(userId);
        return ResponseEntity.ok(userCoupons);
    }
}