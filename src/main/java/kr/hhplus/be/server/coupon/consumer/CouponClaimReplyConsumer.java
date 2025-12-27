package kr.hhplus.be.server.coupon.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hhplus.be.server.coupon.CouponClaimReplyAwaiter;
import kr.hhplus.be.server.coupon.messages.CouponClaimRepliedMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CouponClaimReplyConsumer {

    @Autowired
    private CouponClaimReplyAwaiter awaiter;

    @Autowired
    private ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${app.kafka.topics.coupon-claim-replied:coupon-claim-replied.v1}",
            concurrency = "${app.kafka.consumers.coupon-reply.concurrency:1}"
    )
    public void onReply(String json) throws Exception {
        CouponClaimRepliedMessage reply = objectMapper.readValue(json, CouponClaimRepliedMessage.class);
        awaiter.complete(reply);
    }
}
