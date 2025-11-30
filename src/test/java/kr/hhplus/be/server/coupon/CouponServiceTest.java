package kr.hhplus.be.server.coupon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=true"
})
public class CouponServiceTest {
    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private CouponService couponService;

    @BeforeEach
    void setUp() {
        couponRepository.deleteAll();
        userCouponRepository.deleteAll();
    }

    @Test
    public void 성공적인_발급() {
        // given
        Coupon givenCoupon = new Coupon(
                "TEST_CODE",
                CouponType.PERCENT,
                BigDecimal.TEN,
                LocalDateTime.now().minusDays(1),  // 어제부터 시작
                LocalDateTime.now().plusDays(7),    // 7일 후 만료
                LocalDateTime.now()
        );
        couponRepository.save(givenCoupon);

        // when
        UserCoupon calimedCoupon = couponService.claimCoupon(1L, givenCoupon.getCouponId());

        // then
        assertEquals(calimedCoupon.getCouponId(), givenCoupon.getCouponId());
    }

    @Test
    public void 유효_기간_만료_예외() {
        // given
        Coupon givenCoupon = new Coupon(
                "TEST_CODE",
                CouponType.PERCENT,
                BigDecimal.TEN,
                LocalDateTime.now().minusDays(7),
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now()
        );
        couponRepository.save(givenCoupon);

        // when && then
        assertThatThrownBy(() -> couponService.claimCoupon(1L, givenCoupon.getCouponId()))
                .isInstanceOf(CouponExpiredException.class);
    }

    @Test
    public void 유효_기간_미도래_예외() {
        // given
        Coupon givenCoupon = new Coupon(
                "TEST_CODE",
                CouponType.PERCENT,
                BigDecimal.TEN,
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(7),
                LocalDateTime.now()
        );
        couponRepository.save(givenCoupon);

        // when && then
        assertThatThrownBy(() -> couponService.claimCoupon(1L, givenCoupon.getCouponId()))
                .isInstanceOf(CouponNotYetAvailableException.class);
    }

    @Test
    public void 쿠폰_미존재_예외() {
        // given
        // when && then
        assertThatThrownBy(() -> couponService.claimCoupon(1L, 100L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("이미 발급받은 쿠폰 재발급 시도 시 예외 발생")
    public void 이미_발급한_쿠폰_재발급_시도_예외() {
        // given
        Long userId = 1L;

        Coupon givenCoupon = new Coupon(
                "TEST_CODE_DUPLICATE",
                CouponType.FIXED,
                BigDecimal.valueOf(5000),
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(7),
                LocalDateTime.now()
        );
        couponRepository.save(givenCoupon);

        couponService.claimCoupon(userId, givenCoupon.getCouponId());

        // when && then
        assertThatThrownBy(() -> couponService.claimCoupon(userId, givenCoupon.getCouponId()))
                .isInstanceOf(CouponAlreadyClaimedException.class) // 새로운 예외 정의 필요
                .hasMessageContaining("ALREADY_CLAIMED");
    }

    @Test
    @DisplayName("보유 쿠폰 목록 조회 성공")
    public void 보유_쿠폰_목록_조회_성공() {
        // given
        Long userId = 1L;

        // 1. 테스트에 사용할 쿠폰 2개 생성
        UserCoupon givenCoupon1 = new UserCoupon(userId, 10L, CouponStatus.CLAIMED);
        UserCoupon givenCoupon2 = new UserCoupon(userId, 20L, CouponStatus.CLAIMED);

        // 2. 저장 (userCouponId가 생성됨)
        var savedCoupon1 = userCouponRepository.save(givenCoupon1);
        userCouponRepository.save(givenCoupon2); // 같은 유저의 다른 쿠폰

        // when
        var coupons = couponService.getUserCoupons(userId);

        // then
        // 1. 조회된 쿠폰의 개수가 2개인지 확인
        assertThat(coupons).hasSize(2);

        // 2. 조회된 쿠폰 목록에 저장된 쿠폰 ID가 포함되어 있는지 확인
        // (UserCoupon 객체의 userCouponId 필드를 추출하여 검증)
        assertThat(coupons)
                .extracting("userCouponId")
                .contains(savedCoupon1.getUserCouponId());
    }

    @Test
    @DisplayName("성공적인 쿠폰 사용 - 상태가 USED로 변경된다")
    void 쿠폰_사용_성공() {
        // given
        Long userId = 1L;

        // 1. 발급할 쿠폰 생성 및 저장 (쿠폰 자체의 ID가 1L이라고 가정)
        Coupon givenCoupon = new Coupon(
                "USE_TEST_CODE",
                CouponType.FIXED,
                BigDecimal.valueOf(5000),
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(7),
                LocalDateTime.now()
        );
        couponRepository.save(givenCoupon);

        // 2. 쿠폰 발급 (claimCoupon을 사용하거나 직접 UserCoupon 생성)
        // claimCoupon을 사용하여 발급 과정을 거치는 것이 테스트의 독립성 측면에서 더 좋습니다.
        UserCoupon claimedCoupon = couponService.claimCoupon(userId, givenCoupon.getCouponId());

        // when
        // 3. 발급받은 쿠폰 ID(userCouponId)로 사용 시도
        UserCoupon usedCoupon = couponService.useCoupon(claimedCoupon.getUserCouponId());

        // then
        // 1. 반환된 쿠폰의 상태가 USED인지 확인
        assertThat(usedCoupon.getCouponStatus()).isEqualTo(CouponStatus.USED);
        // 2. 사용 일시(usedAt)가 null이 아닌지 확인
        assertThat(usedCoupon.getUsedAt()).isNotNull();
        // 3. DB에서 직접 조회하여 상태가 변경되었는지 다시 확인 (트랜잭션 검증)
        UserCoupon verifiedCoupon = userCouponRepository.findById(usedCoupon.getUserCouponId()).get();
        assertThat(verifiedCoupon.getCouponStatus()).isEqualTo(CouponStatus.USED);
    }

    @Test
    @DisplayName("이미 사용된 쿠폰 재사용 시도 - 예외 발생")
    void 이미_사용된_쿠폰_재사용_예외() {
        // given
        Long userId = 1L;

        // 1. 쿠폰 생성 및 발급
        Coupon givenCoupon = new Coupon(
                "USED_REUSE_TEST",
                CouponType.FIXED,
                BigDecimal.valueOf(1000),
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(7),
                LocalDateTime.now()
        );
        couponRepository.save(givenCoupon);

        UserCoupon claimedCoupon = couponService.claimCoupon(userId, givenCoupon.getCouponId());

        // 2. 쿠폰 사용 성공 (USED 상태로 변경)
        couponService.useCoupon(claimedCoupon.getUserCouponId());

        // when && then
        // 3. 동일 쿠폰 ID(userCouponId)로 재사용 시도 시 예외 발생
        assertThatThrownBy(() -> couponService.useCoupon(claimedCoupon.getUserCouponId()))
                .isInstanceOf(CouponAlreadyUsedException.class) // 새로운 예외 정의 필요
                .hasMessageContaining("ALREADY_USED");
    }

    @Test
    @DisplayName("존재하지 않는 UserCouponId로 사용 시도 - 예외 발생")
    void 미존재_쿠폰_사용_예외() {
        // given
        Long nonExistentUserCouponId = 9999L;

        // when && then
        // findById.get()을 사용했다면 NoSuchElementException이 발생합니다.
        assertThatThrownBy(() -> couponService.useCoupon(nonExistentUserCouponId))
                .isInstanceOf(NoSuchElementException.class);
    }
}
