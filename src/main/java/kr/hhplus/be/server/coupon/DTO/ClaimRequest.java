package kr.hhplus.be.server.coupon.dto;

public record ClaimRequest(Long couponId, Long userId, String requestId) {
}