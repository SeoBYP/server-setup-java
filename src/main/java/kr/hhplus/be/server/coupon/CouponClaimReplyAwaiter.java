package kr.hhplus.be.server.coupon;

import kr.hhplus.be.server.coupon.messages.CouponClaimRepliedMessage;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CouponClaimReplyAwaiter {
    private final Map<String, CompletableFuture<CouponClaimRepliedMessage>> pending =
            new ConcurrentHashMap<>();

    public CompletableFuture<CouponClaimRepliedMessage> register(String requestId) {
        CompletableFuture<CouponClaimRepliedMessage> f = new CompletableFuture<>();
        pending.put(requestId, f);
        return f;
    }

    public void complete(CouponClaimRepliedMessage reply) {
        if (reply == null || reply.requestId() == null) return;
        CompletableFuture<CouponClaimRepliedMessage> f = pending.get(reply.requestId());
        if (f != null) f.complete(reply);
    }

    public void remove(String requestId) {
        if (requestId == null) return;
        pending.remove(requestId);
    }

}
