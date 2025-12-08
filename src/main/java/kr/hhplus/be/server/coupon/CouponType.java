package kr.hhplus.be.server.coupon;

public enum CouponType {
    PERCENT("0","퍼센트"),
    FIXED("1", "고정");

    private final String code;
    private final String desc;

    CouponType(String code, String desc)
    {
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
