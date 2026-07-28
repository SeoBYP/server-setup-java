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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
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

    @Autowired
    @Qualifier("couponReplyExecutor")
    private ExecutorService couponReplyExecutor;

    /** Kafka reply 대기 상한. 초과 시 504로 응답한다. */
    private static final long REPLY_TIMEOUT_SECONDS = 5;

    /**
     * 선착순 쿠폰 발급 (Kafka Request-Reply, 동기 응답)
     *
     * CompletableFuture를 반환해 Spring MVC의 비동기 처리를 사용한다.
     *
     * 이전 구현은 컨트롤러 안에서 future.get(5s)로 블로킹했다.
     * 그 경우 Kafka 왕복이 끝날 때까지 **서블릿 스레드가 요청 하나에 묶인다.**
     * 부하 테스트에서 Tomcat 스레드 202개 중 196개가
     * CompletableFuture$Signaller.block 상태로 확인되어 풀이 고갈됐다.
     *
     * 반환형을 CompletableFuture로 바꾸면 대기 구간 동안 서블릿 스레드가 반납되고,
     * reply가 도착한 뒤에만 응답 조립에 스레드를 쓴다.
     */
    @PostMapping("/claim/{couponId}")
    public CompletableFuture<ResponseEntity<UserCoupon>> claimCoupon(
            @PathVariable Long couponId,
            @RequestBody ClaimRequest claimRequest) {

        Long userId = claimRequest.userId();
        String requestId = UUID.randomUUID().toString();

        // 1) reply await 등록
        CompletableFuture<CouponClaimRepliedMessage> future = replyAwaiter.register(requestId);

        // 2) kafka produce (key=userId → 파티션 분산)
        try {
            claimProducer.send(new CouponClaimRequestedMessage(
                    requestId,
                    couponId,
                    userId,
                    Instant.now().toEpochMilli()
            ));
        } catch (RuntimeException e) {
            replyAwaiter.remove(requestId);
            throw e;
        }

        // 3) 대기는 논블로킹. 응답 조립은 전용 풀에서 수행해
        //    Kafka 컨슈머 스레드가 DB 조회로 막히지 않게 한다.
        return future
                .orTimeout(REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .<ResponseEntity<UserCoupon>>handleAsync((reply, ex) -> {
                    if (ex != null) {
                        return ResponseEntity.<UserCoupon>status(HttpStatus.GATEWAY_TIMEOUT).build();
                    }
                    return toResponse(reply);
                }, couponReplyExecutor)
                .whenComplete((result, ex) -> replyAwaiter.remove(requestId));
    }

    private ResponseEntity<UserCoupon> toResponse(CouponClaimRepliedMessage reply) {
        if (!reply.success()) {
            String code = reply.errorCode() == null ? "" : reply.errorCode();

            if ("ALREADY_CLAIMED".equals(code)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
            if ("COUPON_NOT_AVAILABLE".equals(code) || "EXPIRED_COUPON".equals(code)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            if ("COUPON_SOLD_OUT".equals(code)) {
                return ResponseEntity.status(HttpStatus.GONE).build(); // 품절은 410으로 구분
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        // 컨슈머가 만든 userCouponId로 PK 단건 조회해서 반환
        return couponService.getUserCoupon(reply.userCouponId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    @GetMapping("/users/{userId}/coupons")
    public ResponseEntity<List<UserCoupon>> getUserCoupons(@PathVariable Long userId) {
        List<UserCoupon> userCoupons = couponService.getUserCoupons(userId);
        return ResponseEntity.ok(userCoupons);
    }
}