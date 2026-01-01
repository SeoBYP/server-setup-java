package kr.hhplus.be.server.coupon.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.coupon.CouponService;
import kr.hhplus.be.server.coupon.UserCoupon;
import kr.hhplus.be.server.coupon.exception.CouponAlreadyClaimedException;
import kr.hhplus.be.server.coupon.exception.CouponExpiredException;
import kr.hhplus.be.server.coupon.exception.CouponNotFoundException;
import kr.hhplus.be.server.coupon.exception.CouponNotYetAvailableException;
import kr.hhplus.be.server.coupon.messages.CouponClaimRepliedMessage;
import kr.hhplus.be.server.coupon.messages.CouponClaimRequestedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class CouponClaimRequestConsumer {
    @Autowired
    private CouponService couponService;

    @Autowired
    private KafkaTemplate<String,String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.kafka.topics.coupon-claim-replied:coupon-claim-replied.v1}")
    private String replyTopic;

    private static final Logger log = LoggerFactory.getLogger(CouponClaimRequestConsumer.class);

    @KafkaListener(
            topics = "${app.kafka.topics.coupon-claim-requested:coupon-claim-requested.v1}",
            concurrency = "${app.kafka.consumers.coupon-claim.concurrency:1}"
    )
    public void onMessage(String json) {
        CouponClaimRepliedMessage reply;
        CouponClaimRequestedMessage req = null;

        try {
            req = objectMapper.readValue(json, CouponClaimRequestedMessage.class);

            // ✅ 비즈니스 로직 실행
            UserCoupon uc = couponService.claimCouponByMessage(
                    req.requestId(), req.userId(), req.couponId()
            );

            reply = new CouponClaimRepliedMessage(
                    req.requestId(),
                    req.couponId(),
                    req.userId(),
                    true,
                    uc.getUserCouponId(),
                    ""
            );

        } catch (CouponAlreadyClaimedException e) {
            reply = new CouponClaimRepliedMessage(
                    req.requestId(), req.couponId(), req.userId(),
                    false, null, "ALREADY_CLAIMED"
            );
        } catch (CouponNotYetAvailableException e) {
            reply = new CouponClaimRepliedMessage(
                    req.requestId(), req.couponId(), req.userId(),
                    false, null, "COUPON_NOT_AVAILABLE"
            );
        } catch (CouponExpiredException e) {
            reply = new CouponClaimRepliedMessage(
                    req.requestId(), req.couponId(), req.userId(),
                    false, null, "EXPIRED_COUPON"
            );
        } catch (CouponNotFoundException e) {
            reply = new CouponClaimRepliedMessage(
                    req.requestId(), req.couponId(), req.userId(),
                    false, null, "COUPON_NOT_FOUND"
            );
        } catch (IllegalStateException e) {
            String code = e.getMessage() == null ? "UNKNOWN" : e.getMessage();
            reply = new CouponClaimRepliedMessage(
                    req.requestId(), req.couponId(), req.userId(),
                    false, null, code
            );
        } catch (Exception e) {
            // ✅ 모든 예외를 catch해서 Kafka에게 "처리 완료"로 알림
            log.error("Unexpected error processing coupon claim: requestId={}, userId={}, couponId={}",
                    req != null ? req.requestId() : "null",
                    req != null ? req.userId() : "null",
                    req != null ? req.couponId() : "null",
                    e
            );

            reply = new CouponClaimRepliedMessage(
                    req.requestId(), req.couponId(), req.userId(),
                    false, null, "INTERNAL_ERROR"
            );
        }

        // ✅ 항상 reply 전송 (예외가 발생해도)
        try {
            kafkaTemplate.send(replyTopic, req.requestId(),
                    objectMapper.writeValueAsString(reply));
        } catch (Exception e) {
            log.error("Failed to send reply: requestId={}", req.requestId(), e);
            // ⚠️ reply 전송 실패는 로그만 남기고 Kafka에게는 메시지 처리 성공으로 알림
            // (무한 재시도 방지)
        }
    }
}