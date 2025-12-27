package kr.hhplus.be.server.coupon.messages;

public record CouponClaimRequestedMessage(
        String requestId,
        Long couponId,
        Long userId,
        long requestedAtEpochMs
) {}
