package kr.hhplus.be.server.coupon;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.stream.Stream;

@Converter(autoApply = true)
public class CouponStatusConverter implements AttributeConverter<CouponStatus, String> {

    @Override
    public String convertToDatabaseColumn(CouponStatus couponStatus) {
        return couponStatus.getCode();
    }

    @Override
    public CouponStatus convertToEntityAttribute(String dbData) {
        if(dbData == null) return null;
        return Stream.of(CouponStatus.values())
                .filter(c -> c.getCode().equals(dbData))
                .findFirst()
                .orElseThrow(IllegalArgumentException::new);
    }
}
