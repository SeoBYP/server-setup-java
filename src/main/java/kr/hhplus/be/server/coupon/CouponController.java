package kr.hhplus.be.server.coupon;

import kr.hhplus.be.server.coupon.dto.ClaimRequest;
import kr.hhplus.be.server.coupon.producer.CouponClaimProducer;
import kr.hhplus.be.server.coupon.CouponClaimReplyAwaiter;
import kr.hhplus.be.server.coupon.messages.CouponClaimRequestedMessage;
import kr.hhplus.be.server.coupon.messages.CouponClaimRepliedMessage;
import kr.hhplus.be.server.coupon.exception.CouponAlreadyClaimedException;
import kr.hhplus.be.server.coupon.exception.CouponExpiredException;
import kr.hhplus.be.server.coupon.exception.CouponNotYetAvailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    @Autowired
    private CouponService couponService;

    @Autowired
    private CouponClaimProducer claimProducer;

    @Autowired
    private CouponClaimReplyAwaiter replyAwaiter;

    @PostMapping("/claim/{couponId}")
    public ResponseEntity<UserCoupon> claimCoupon(@PathVariable Long couponId, @RequestBody ClaimRequest claimRequest) {
        Long userId = claimRequest.userId();

        String requestId = UUID.randomUUID().toString();

        // 1) reply await 등록
        var future = replyAwaiter.register(requestId);

        // 2) kafka produce (key=couponId)
        claimProducer.send(new CouponClaimRequestedMessage(
                requestId,
                couponId,
                userId,
                Instant.now().toEpochMilli()
        ));

        // 3) 3초 대기 후 결과에 따라 응답
        final CouponClaimRepliedMessage reply;
        try {
            reply = future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            replyAwaiter.remove(requestId);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
        } finally {
            replyAwaiter.remove(requestId);
        }

        if (!reply.success()) {
            String code = reply.errorCode() == null ? "" : reply.errorCode();

            if ("ALREADY_CLAIMED".equals(code)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
            if ("COUPON_NOT_AVAILABLE".equals(code) || "EXPIRED_COUPON".equals(code)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            if ("COUPON_SOLD_OUT".equals(code)) {
                return ResponseEntity.status(HttpStatus.GONE).build(); // 품절은 410 같은 코드가 실습에서 구분하기 좋음
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        // 동기 반환 요구사항: 컨슈머가 만든 userCouponId로 DB 재조회해서 반환
        // (reply에 쿠폰 상세를 다 실어도 되지만, 실습에선 ID만 전달하고 조회가 깔끔)
        return couponService.getUserCoupons(reply.userId()).stream()
                .filter(uc -> uc.getUserCouponId().equals(reply.userCouponId()))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    @GetMapping("/users/{userId}/coupons")
    public ResponseEntity<List<UserCoupon>> getUserCoupons(@PathVariable Long userId) {
        List<UserCoupon> userCoupons = couponService.getUserCoupons(userId);
        return ResponseEntity.ok(userCoupons);
    }
}