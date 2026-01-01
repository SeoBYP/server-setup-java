package kr.hhplus.be.server.coupon.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.coupon.CouponFacade;
import kr.hhplus.be.server.coupon.UserCoupon;
import kr.hhplus.be.server.coupon.exception.CouponAlreadyClaimedException;
import kr.hhplus.be.server.coupon.exception.CouponAlreadyUsedException;
import kr.hhplus.be.server.coupon.exception.CouponExpiredException;
import kr.hhplus.be.server.coupon.exception.CouponNotFoundException;
import kr.hhplus.be.server.coupon.exception.CouponNotYetAvailableException;
import kr.hhplus.be.server.coupon.exception.CouponSoldOutException;
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
    private CouponFacade couponFacade;

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

            UserCoupon uc = couponFacade.claimCoupon(
                    req.userId(), req.couponId(), req.requestId()
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
        } catch (CouponSoldOutException e) {
            reply = new CouponClaimRepliedMessage(
                    req.requestId(), req.couponId(), req.userId(),
                    false, null, "COUPON_SOLD_OUT"
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
        } catch (CouponAlreadyUsedException e) {
            // 혹시 다른 경로에서 진짜 USED가 올라오면 명시적으로 처리
            reply = new CouponClaimRepliedMessage(
                    req.requestId(), req.couponId(), req.userId(),
                    false, null, "ALREADY_USED"
            );
        } catch (IllegalStateException e) {
            String code = e.getMessage() == null ? "UNKNOWN" : e.getMessage();
            reply = new CouponClaimRepliedMessage(
                    req.requestId(), req.couponId(), req.userId(),
                    false, null, code
            );
        } catch (Exception e) {
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

        try {
            kafkaTemplate.send(replyTopic, req.requestId(),
                    objectMapper.writeValueAsString(reply));
        } catch (Exception e) {
            log.error("Failed to send reply: requestId={}", req.requestId(), e);
        }
    }
}