package kr.hhplus.be.server.order;

public enum OrderStatus {
    ORDERED("O", "주문 완료"),
    SHIPPING("S", "배송 중"),
    CANCELLED("C", "주문 취소");

    private final String code;
    private final String desc;

    OrderStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }


    public String getCode() {
        return code;
    }

    public String getDesc(){
        return desc;
    }
}