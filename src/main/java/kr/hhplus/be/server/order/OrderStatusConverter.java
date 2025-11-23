package kr.hhplus.be.server.order;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.stream.Stream;

@Converter(autoApply = true)
public class OrderStatusConverter implements AttributeConverter<OrderStatus, String> {

    @Override // Enum -> DB (저장 시)
    public String convertToDatabaseColumn(OrderStatus attribute) {
        return attribute.getCode(); // DB에 'O', 'S', 'C'가 저장됨
    }

    @Override // DB -> Enum (조회 시)
    public OrderStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;

        return Stream.of(OrderStatus.values())
                .filter(c -> c.getCode().equals(dbData))
                .findFirst()
                .orElseThrow(IllegalArgumentException::new);
    }
}
