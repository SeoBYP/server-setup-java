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

    @KafkaListener(
            topics = "${app.kafka.topics.coupon-claim-requested:coupon-claim-requested.v1}",
            concurrency = "${app.kafka.consumers.coupon-claim.concurrency:1}"
    )
    public void onMessage(String json) throws Exception {
        CouponClaimRequestedMessage req = objectMapper.readValue(json, CouponClaimRequestedMessage.class);

        CouponClaimRepliedMessage reply;

        try {
            UserCoupon uc = couponService.claimCouponByMessage(req.requestId(), req.userId(), req.couponId());
            reply = new CouponClaimRepliedMessage(
                    req.requestId(),
                    req.couponId(),
                    req.userId(),
                    true,
                    uc.getUserCouponId(),
                    ""
            );
        } catch (CouponAlreadyClaimedException e) {
            reply = new CouponClaimRepliedMessage(req.requestId(), req.couponId(), req.userId(), false, null, "ALREADY_CLAIMED");
        } catch (CouponNotYetAvailableException e) {
            reply = new CouponClaimRepliedMessage(req.requestId(), req.couponId(), req.userId(), false, null, "COUPON_NOT_AVAILABLE");
        } catch (CouponExpiredException e) {
            reply = new CouponClaimRepliedMessage(req.requestId(), req.couponId(), req.userId(), false, null, "EXPIRED_COUPON");
        } catch (CouponNotFoundException e) {
            reply = new CouponClaimRepliedMessage(req.requestId(), req.couponId(), req.userId(), false, null, "COUPON_NOT_FOUND");
        } catch (IllegalStateException e) {
            // sold out 등
            String code = e.getMessage() == null ? "UNKNOWN" : e.getMessage();
            reply = new CouponClaimRepliedMessage(req.requestId(), req.couponId(), req.userId(), false, null, code);
        } catch (Exception e) {
            reply = new CouponClaimRepliedMessage(req.requestId(), req.couponId(), req.userId(), false, null, "INTERNAL_ERROR");
        }

        // ✅ reply는 requestId 키로 보내면, API 쪽에서 매칭하기 쉬움
        kafkaTemplate.send(replyTopic, req.requestId(), objectMapper.writeValueAsString(reply));
    }

}
