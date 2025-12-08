package kr.hhplus.be.server.wallet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=true"
})
@Transactional  // 각 테스트 후 롤백
public class WalletServiceTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletRepository walletRepository;

    @BeforeEach
    void setUp() {
        walletRepository.deleteAll();
    }

    @Test
    @DisplayName("정상 충전 - 잔액이 증가한다")
    void 충전_성공시_잔액증가() {
        // given
        var value = BigDecimal.valueOf(1000);
        Wallet wallet = new Wallet(1L, value);
        walletRepository.save(wallet);

        // when
        walletService.charge(1L, BigDecimal.valueOf(500));

        // then
        BigDecimal balance = walletService.getBalance(1L);
        assertEquals(value.add(BigDecimal.valueOf(500)), balance);
    }

    @Test
    @DisplayName("소수점 단위 정상 충전 - 잔액이 증가한다")
    void 소수점_단위_충전_성공시_잔액증가() {
        // given
        var value = BigDecimal.valueOf(1000.50);
        Wallet wallet = new Wallet(1L, value);
        walletRepository.save(wallet);

        // when
        walletService.charge(1L, BigDecimal.valueOf(500.255));

        // then
        var result = value.add(BigDecimal.valueOf(500.255));
        BigDecimal balance = walletService.getBalance(1L);
        assertEquals(result, balance);
    }

    @Test
    @DisplayName("음수 충전 시도 - 예외 발생")
    void 음수_충전시_예외발생() {
        // given
        Wallet wallet = new Wallet(1L, BigDecimal.valueOf(1000));
        walletRepository.save(wallet);

        // when
        assertThatThrownBy(() -> walletService.charge(1L, BigDecimal.valueOf(-500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount>0");

        // then (실패하면 금액은 그대로 유지된다.)
        Optional<Wallet> wallet1 = walletRepository.findById(1L);
        assertEquals(wallet1.get().getBalance(), BigDecimal.valueOf(1000));
    }

    @Test
    @DisplayName("NULL 충전 시도 - 예외 발생")
    void NULL_충전_예외발생() {
        // given
        Wallet wallet = new Wallet(1L, BigDecimal.valueOf(1000));
        walletRepository.save(wallet);

        // when
        assertThatThrownBy(() -> walletService.charge(1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount>0");

        // then (실패하면 금액은 그대로 유지된다.)
        var wallet1 = walletRepository.findById(1L);
        assertEquals(wallet1.get().getBalance(), BigDecimal.valueOf(1000));
    }

    @Test
    @DisplayName("출금 성공시 잔액 감소")
    void 출금_성공시_잔액_감소() {
        // given
        var value = BigDecimal.valueOf(1000);
        Wallet wallet = new Wallet(1L, value);
        walletRepository.save(wallet);

        // when
        walletService.debit(1L, BigDecimal.valueOf(500));

        // then
        var wallet1 = walletRepository.findById(1L);
        assertEquals(value.subtract(BigDecimal.valueOf(500)), wallet1.get().getBalance());
    }

    @Test
    @DisplayName("전액 출금시 잔액이 0이된다.")
    void 전액_출금시_잔액이_0이된다() {
        // given
        var value = BigDecimal.valueOf(1000);
        Wallet wallet = new Wallet(1L, value);
        walletRepository.save(wallet);

        // when
        walletService.debit(1L, BigDecimal.valueOf(1000));

        // then
        var wallet1 = walletRepository.findById(1L);
        assertEquals(BigDecimal.ZERO, wallet1.get().getBalance());
    }

    @Test
    @DisplayName("잔액 부족 - InsufficientBalanceException 발생")
    void 잔액_부족_에러발생(){
        // given
        var value = BigDecimal.valueOf(1000);
        Wallet wallet = new Wallet(1L, value);
        walletRepository.save(wallet);

        // when
        assertThatThrownBy(() -> walletService.debit(1L, BigDecimal.valueOf(1200)))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessage("INSUFFICIENT_BALANCE");

        // then (실패하면 금액은 그대로 유지된다.)
        var wallet1 = walletRepository.findById(1L);
        assertEquals(wallet1.get().getBalance(), value);
    }

    @Test
    @DisplayName("음수 금액 출금 시도 - 예외 발생")
    void 음수_금액_출금_에러발생(){
        // given
        var value = BigDecimal.valueOf(1000);
        Wallet wallet = new Wallet(1L, value);
        walletRepository.save(wallet);

        // when
        assertThatThrownBy(() -> walletService.debit(1L, BigDecimal.valueOf(-200)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount>0");

        // then (실패하면 금액은 그대로 유지된다.)
        var wallet1 = walletRepository.findById(1L);
        assertEquals(wallet1.get().getBalance(), value);
    }

    @Test
    @DisplayName("null 금액 출금 시도 - 예외 발생")
    void NULL_금액_출금_시도_에러발생(){
        // given
        var value = BigDecimal.valueOf(1000);
        Wallet wallet = new Wallet(1L, value);
        walletRepository.save(wallet);

        // when
        assertThatThrownBy(() -> walletService.debit(1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount>0");

        // then (실패하면 금액은 그대로 유지된다.)
        var wallet1 = walletRepository.findById(1L);
        assertEquals(wallet1.get().getBalance(), value);
    }

    @Test
    @DisplayName("초기 잔액 생성 테스트")
    void 초기_잔액_생성_성공(){
        // given
        var value = BigDecimal.valueOf(1000);
        Wallet wallet = new Wallet(1L, value);

        // then
        assertThat(wallet.getUserId()).isEqualTo(1L);
        assertThat(wallet.getBalance()).isEqualTo(value);
        assertThat(wallet.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("NULL 초기 잔액 - 0으로 생성")
    void 기본_잔액_0_으로_생성_성공(){
        Wallet wallet = new Wallet(1L, null);

        // then
        assertThat(wallet.getUserId()).isEqualTo(1L);
        assertThat(wallet.getBalance()).isEqualTo(BigDecimal.ZERO);
        assertThat(wallet.getCreatedAt()).isNotNull();
    }
}