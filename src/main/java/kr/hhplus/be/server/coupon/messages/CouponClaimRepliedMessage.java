package kr.hhplus.be.server.coupon.messages;

public record CouponClaimRepliedMessage(
        String requestId,
        Long couponId,
        Long userId,
        boolean success,
        Long userCouponId,
        String errorCode
) {}

