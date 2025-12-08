package kr.hhplus.be.server.coupon;

public enum CouponStatus {
    CLAIMED("0","발급"),
    USED("1","사용");

    private final String code;
    private final String desc;

    CouponStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
