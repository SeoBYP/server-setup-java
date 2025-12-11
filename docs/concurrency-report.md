# 동시성 제어 전략 및 테스트 보고서

본 문서는 e-커머스 상품 주문 서비스에서 발생할 수 있는 대표적인 동시성 이슈에 대해

* 문제 상황
* 해결 전략 (DB/락/멱등성 설계)
* 멀티스레드 테스트 설계 및 결과

를 정리한 보고서입니다.

---

## 1. 주문 생성 동시성 (재고 + 잔액 + 멱등성)

### 1-1. 문제 상황

1. **동일 사용자가 같은 주문을 여러 번 요청하는 경우**

* 네트워크 재시도, 프론트 중복 클릭 등으로 같은 주문이 여러 번 서버에 도착할 수 있음.
* 문제:

    * 주문이 여러 건 생성될 수 있음.
    * 그에 따라 **재고가 여러 번 감소**하고, **잔액도 여러 번 차감**되어 정합성이 깨질 수 있음.

2. **여러 사용자가 동시에 같은 상품을 주문하는 경우**

* 재고가 10개인데, 10명 이상이 동시에 주문을 넣는 상황.
* 문제:

    * 동시에 재고를 읽고 차감하면, **재고가 0 미만으로 내려가거나**,
    * 실제 재고보다 더 많이 판매(oversell)할 수 있음.

### 1-2. 해결 전략

1. **주문 멱등성(Idempotency)**

* `orders` 테이블에 `idempotency_key` 컬럼을 두고 `UNIQUE` 제약을 걸어,

    * 같은 키로 들어온 요청은 **하나의 주문만 생성**되도록 설계.
* 서비스 단에서는

    * `idempotency_key` 기준으로 기존 주문을 조회하고,
    * 이미 처리된 경우 새로 만들지 않고 기존 결과를 재사용.

2. **재고/잔액 동시성 제어 (비관적 락)**

* `Product`, `Wallet` 조회 시 다음과 같이 **비관적 락(PESSIMISTIC_WRITE)** 기반의 전용 조회 메서드를 사용.

예시 (Wallet):

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select w from Wallet w where w.userId = :userId")
Optional<Wallet> findForUpdate(@Param("userId") Long userId);
```

* 주문 생성 트랜잭션에서

    * `walletRepository.findForUpdate(userId)` 로 잔액 행 잠금
    * `productRepository.findForUpdate(productId)` (가정)로 상품 행 잠금
* 잠금 이후에만

    * 잔액 차감 (`wallet.debit`)
    * 재고 차감 (`product.decreaseStock`)
      를 수행함으로써 **동시에 접근해도 잔액/재고가 꼬이지 않도록** 보장.

3. **트랜잭션 경계**

* 주문 생성 API는 하나의 트랜잭션 안에서

    1. 재고 확인 및 감소
    2. 지갑 잔액 확인 및 차감
    3. 주문 및 주문아이템 저장
    4. Outbox 이벤트 기록
* 위 작업 중 하나라도 실패하면 롤백되어, **부분만 반영되는 상황을 방지**.

### 1-3. 테스트 설계 및 결과

#### (1) `주문_생성_경쟁_테스트`

* 클래스: `OrderConcurrencyTest`
* 메서드: `주문_생성_경쟁_테스트()`

**시나리오**

* 한 사용자(`userId = 1`)가 같은 주문을 10개 스레드에서 동시에 요청.
* 모든 스레드가 동일한 `idempotencyKey` 를 사용.

**검증**

1. DB에 생성된 주문은 **1건**인지

   ```java
   var orders = orderRepository.findAll().stream()
       .filter(o -> o.getUserId().equals(userId))
       .toList();
   assertEquals(1, orders.size());
   ```

2. 지갑 잔액이 **한 번만 차감**되었는지
   (총 결제 금액이 1,000원이라고 가정하면, 최종 잔액은 `10,000 - 1,000 = 9,000` 이어야 함)

   ```java
   var wallet = walletRepository.findById(userId).get();
   assertTrue(BigDecimal.valueOf(9000).compareTo(wallet.getBalance()) == 0);
   ```

3. 각 상품의 재고가 **한 번만 차감**되었는지
   (초기 재고 10, 주문 수량 5 → 최종 재고 5)

   ```java
   assertEquals(5, p1.getStock());
   assertEquals(5, p2.getStock());
   ```

**결과**

* 10개의 동시 요청에도 불구하고

    * 주문은 1건만 생성
    * 사용자 잔액/상품 재고는 한 번만 차감
* → **idempotency key + 비관적 락 조합이 정상 동작함**을 검증.

---

#### (2) `다수_유저_주문_생성_경쟁_테스트`

* 클래스: `OrderConcurrencyTest`
* 메서드: `다수_유저_주문_생성_경쟁_테스트()`

**시나리오**

* 재고가 10개인 상품 1개
* 10명의 서로 다른 유저가 동시에 “2개씩” 주문 시도
* 이론상 최대 성공 가능 주문 수: `10 / 2 = 5`

**검증**

1. 실제 성공 횟수가 예상치(5)와 같은지

   ```java
   int expectedSuccess = initialStock / perUserQuantity;
   assertEquals(expectedSuccess, successCount.get());
   assertEquals(userCount - expectedSuccess, failCount.get());
   ```

2. DB에 생성된 주문 수 = 성공 횟수

   ```java
   var orders = orderRepository.findAll();
   assertEquals(expectedSuccess, orders.size());
   ```

3. 최종 재고가 0인지

   ```java
   var updatedProduct = productRepository.findById(productId).get();
   assertEquals(0, updatedProduct.getStock());
   ```

4. 성공한 유저의 잔액만 차감되었는지, 실패한 유저는 그대로인지

**결과**

* 동시에 주문이 몰려도,

    * 성공한 주문 수는 재고 한도를 넘지 않고,
    * 실제 재고는 0에서 멈추며,
    * 실패한 유저의 지갑은 변경되지 않음.
* → **재고/잔액 관련 동시성 제어가 올바르게 동작함**을 검증.

---

## 2. 지갑(포인트) 충전/차감 동시성

### 2-1. 문제 상황

1. **동시에 여러 번 충전 요청**

* 사용자가 여러 번 충전 버튼을 누르거나, API 재시도로 인해 충전 요청이 동시에 여러 번 들어오는 상황.
* 문제:

    * 일부 요청이 반영되지 않거나 (lost update),
    * 중복 row 생성 등으로 잔액과 실제 충전 내역이 맞지 않을 수 있음.

2. **동시에 여러 번 차감 요청**

* 주문 여러 건이 거의 동시에 발생하면서, 같은 지갑에서 동시에 포인트를 차감하는 상황.
* 문제:

    * 잔액 체크 이전에 동시에 읽어버리면 **음수 잔액**이 될 수 있음.
    * 또는 일부 차감이 덮어씌워져 lost update 발생.

### 2-2. 해결 전략

* 모든 지갑 변경(충전/차감)은 `WalletService`에서 처리하며,

    * `walletRepository.findForUpdate(userId)` 를 통해 **비관적 락**을 사용.
* `Wallet` 엔티티는

    * `charge(amount)` – 0 이상만 허용, 단순 덧셈
    * `debit(amount)` – 잔액 부족 시 `InsufficientBalanceException` 발생 후 차감하지 않음
* 하나의 트랜잭션에서 한 번에 읽고, 검증하고, 업데이트하여
  **동시에 여러 호출이 들어와도 일관된 balance** 를 유지.

### 2-3. 테스트 설계 및 결과

#### (1) `동시_지갑_충전_정합성_테스트`

* 클래스: `WalletConcurrencyTest`
* 메서드: `동시_지갑_충전_정합성_테스트()`

**시나리오**

* 초기 잔액 0원인 지갑에 대해
* 10개 스레드가 동시에 1,000원씩 충전 요청

**검증**

1. 지갑 row는 단 하나인지

   ```java
   assertEquals(1, wallets.size());
   ```

2. 최종 잔액이 `1,000 * 10 = 10,000` 인지

   ```java
   var expectedValue = chargeAmount.multiply(BigDecimal.valueOf(threadCount));
   assertTrue(walletService.getBalance(userId).compareTo(expectedValue) == 0);
   ```

**결과**

* 10개의 concurrent 충전 요청이 모두 정확히 반영되어 잔액이 10,000원이 됨.
* → **비관적 락으로 lost update 없이 합산이 정확히 이루어짐**을 검증.

---

#### (2) `다수_유저_지갑_충전_경쟁_테스트`

* 클래스: `WalletConcurrencyTest`
* 메서드: `다수_유저_지갑_충전_경쟁_테스트()`

**시나리오**

* 3명의 서로 다른 사용자에 대해 각자

    * 동시에 10번씩 1,000원 충전 (총 30개의 요청)

**검증**

1. 각 유저의 최종 잔액 = `1,000 * 10 = 10,000`

2. `wallets` 테이블 row 수 = 유저 수(3)

**결과**

* 유저별로 독립적인 row 하나만 유지되며,
* 동시 충전이 들어와도 각 유저의 잔액이 정확히 누적됨.

---

#### (3) `동시_잔액_차감_경쟁_테스트`

* 클래스: `WalletConcurrencyTest`
* 메서드: `동시_잔액_차감_경쟁_테스트()`

**시나리오**

* 초기 잔액: 1,000원
* 차감 금액: 300원
* 10개 스레드가 동시에 300원씩 `debit` 호출

**검증**

1. 최종 지갑 row는 하나인지

2. 총 성공 횟수만큼 잔액이 줄어들었는지

   ```java
   var totalUsed = withdrawAmount.multiply(BigDecimal.valueOf(successCount.intValue()));
   var expectedValue = initialBalance.subtract(totalUsed);
   assertTrue(walletService.getBalance(userId).compareTo(expectedValue) == 0);
   ```

3. (선택) 성공 횟수가 이론상 최대치(3회)를 넘지 않도록 검증 가능

**결과**

* 비관적 락 추가 후:

    * 성공적으로 차감된 횟수와 최종 잔액이 항상 일치.
    * 음수 잔액 발생 케이스가 재현되지 않음.
* → **동시 차감 시에도 음수 잔액 및 lost update 없이 정합성이 유지됨**을 검증.

---

## 3. 쿠폰 발급 동시성

### 3-1. 문제 상황

1. **동일 사용자가 같은 쿠폰을 여러 번 발급 요청**

* API 재시도, 중복 클릭 등으로 동일 쿠폰에 대해 여러 번 발급 요청이 동시 도착.
* 문제:

    * 동일 유저에게 같은 쿠폰이 여러 장 발급될 수 있음.

2. **여러 사용자가 동시에 같은 쿠폰을 발급 요청**

* 단일 쿠폰 엔트리(논리적으로 한 장만 의미)가 존재하는 경우,

    * 여러 사용자가 동시에 같은 쿠폰 ID에 대해 발급 요청 가능.
* 문제:

    * 한 장만 의미하는 쿠폰이 여러 유저에게 중복 발급될 수 있음.

### 3-2. 해결 전략

* `user_coupons` 테이블에 **유니크 제약** 적용:

    * `(user_id, coupon_id)` unique → 한 유저는 특정 쿠폰을 최대 1번만 소유.
* 전역 1회 쿠폰 개념의 경우 서비스 레벨에서

    * 동시에 여러 유저가 발급 요청 시 **선착순 1명만 성공**하도록 도메인 정책 정의.
* 예외 상황은 `CouponAlreadyClaimedException` 등 도메인 예외로 명시.

### 3-3. 테스트 설계 및 결과

#### (1) `동시_쿠폰_발급_경쟁_테스트`

* 클래스: `CouponConcurrencyTest`
* 메서드: `동시_쿠폰_발급_경쟁_테스트()`

**시나리오**

* 하나의 사용자(`userId = 1`)가
* 특정 쿠폰 ID에 대해 10개 스레드로 동시에 발급 요청

**검증**

1. 성공 횟수 = 1, 실패 횟수 = 9

   ```java
   assertEquals(1, successCount.get());
   assertEquals(threadCount - 1, failCount.get());
   ```

2. DB 에서 해당 `(userId, couponId)` 에 해당하는 `UserCoupon` row 는 1건만 존재

**결과**

* 동일 쿠폰에 대한 여러 동시 요청 중

    * 최초 1건만 성공, 나머지는 `CouponAlreadyClaimedException` 으로 실패.
* → **동일 유저의 중복 발급 방지 동시성 제어가 정상 동작함**을 검증.

---

#### (2) `다수_유저_쿠폰_발급_경쟁_테스트`

* 클래스: `CouponConcurrencyTest`
* 메서드: `다수_유저_쿠폰_발급_경쟁_테스트()`

**시나리오**

* 10명의 서로 다른 사용자가
* 동일 쿠폰 ID에 대해 동시에 발급 요청

**검증**

1. 성공 횟수 = 1, 실패 횟수 = 9

2. DB 에 `couponId` 기준으로 `UserCoupon` row 가 1건만 존재

**결과**

* 여러 사용자가 동시에 발급 요청을 보내도,

    * 오직 한 사용자만 성공하고,
    * 나머지는 `CouponAlreadyClaimedException` 으로 실패.
* → **글로벌 1회 쿠폰 발급의 동시성 제어가 안정적으로 동작함**을 검증.

---

## 4. Outbox 패턴 및 외부 전송 안정성

### 4-1. 문제 상황

* 주문/결제는 DB 트랜잭션으로 안전하게 커밋되었지만,

    * 외부 데이터 플랫폼으로의 이벤트 전송에서 실패할 수 있음.
* 문제:

    * 주문은 성공했는데, 데이터 플랫폼의 통계/로그에는 누락되는 **데이터 불일치** 발생.
    * 서버 재시도 시 중복 전송 문제도 고려해야 함.

### 4-2. 해결 전략

1. **Outbox 패턴**

* 주문 트랜잭션 내에서 `outbox` 테이블에 이벤트 레코드를 함께 기록.
* Worker(`OutboxWorker`)가 주기적으로 `status = PENDING` 인 레코드를 조회하여 외부로 전송.
* 전송 결과에 따라

    * 성공 시: `processed = true` 및 필요 시 상태 전이
    * 실패 시: `status = FAILED` 로 마킹

2. **외부 전송 실패 처리**

* 외부 API 전송 결과가 false/예외인 경우

    * Outbox row 의 상태를 `FAILED` 로 변경해서
    * 실패 내역을 추적 가능하게 하고,
    * 필요 시 재전송 정책을 유연하게 설정하도록 설계.

### 4-3. 테스트 설계 및 결과

#### (1) 주문 생성 → Consumer 실행 통합 테스트

* 클래스: `OrderPopularProductOutboxIntegrationTest`
* 메서드: `주문_생성_후_컨슈머_실행시_인기상품_판매수량_증가_및_아웃박스_processed_갱신_성공()`

**시나리오**

1. 주문 생성

    * 상품/지갑/인기상품(PopularProduct) 초기값 세팅
    * `orderService.createOrder(...)` 호출
2. 주문 생성 시 Outbox 에 `ORDER` 이벤트가 1건 기록되었는지 확인
3. 별도의 Consumer(`popularProductConsumer.processOrderEvents()`) 호출

**검증**

* 주문 직후:

    * Wallet 잔액이 결제 금액만큼 차감되었는지
    * Outbox 에 `aggregateType = ORDER` 인 이벤트가 1건 생성되었는지
* Consumer 실행 후:

    * PopularProduct 의 `salesQuantity` 가 주문 수량만큼 증가했는지
    * Outbox 의 `processed` 플래그가 `true` 로 변경되었는지

**결과**

* 주문 트랜잭션과 Outbox 기록이 함께 커밋되고,
* 이후 Consumer 실행을 통해 인기상품 통계와 Outbox 상태가 일관되게 갱신되는 것을 검증.

---

#### (2) `Outbox_전송실패시_STATUS_FAILED로_변경된다`

* 클래스: `OutboxWorkerTest`

**시나리오**

1. 상품/지갑/주문 생성 → Outbox 에 `PENDING` 상태 이벤트 생성
2. `DataPlatformTransmitter`를 Mock 처리:

    * `transmitter.send(...)` 호출 시 항상 `false` 반환
3. `outboxWorker.processPendingEvents()` 실행

**검증**

* Worker 실행 전: Outbox 상태 = `PENDING`
* Worker 실행 후: Outbox 상태 = `FAILED`

**결과**

* 외부 전송 실패 시 Outbox 상태를 `FAILED` 로 정확히 업데이트함을 검증.
* 이를 통해

    * 주문은 정상 커밋되었지만
    * 외부 전송이 실패한 케이스를 추적/관리할 수 있음.

---

## 5. 요약

본 프로젝트는 다음과 같은 동시성/정합성 요구사항을 만족하도록 설계하고, 멀티스레드 테스트로 검증했습니다.

1. **재고 및 잔액 동시성 제어**

    * `SELECT ... FOR UPDATE`(비관적 락)를 사용한 Product/Wallet 행 잠금
    * 다수 사용자/다수 스레드 환경에서도 재고 oversell 및 음수 잔액 방지

2. **주문 멱등성**

    * `idempotency_key` 유니크 제약과 서비스 로직을 통해
    * 동일 요청의 중복 주문 생성/중복 결제를 방지

3. **쿠폰 발급 동시성**

    * `(user_id, coupon_id)` 유니크 제약과 트랜잭션 제어를 통해
    * 동일 유저/글로벌 1회 쿠폰 모두 중복 발급 방지

4. **Outbox 패턴 기반 외부 연동 안정성**

    * 주문 커밋과 외부 전송을 분리하고,
    * Worker + 상태 필드를 통해 성공/실패를 명확히 관리

각 동시성 시나리오는 실제 멀티스레드 테스트(`ExecutorService + CountDownLatch`)로 재현 및 검증하였으며,
테스트 결과에 따라 락 전략 및 예외 처리 정책을 보완하여 최종적으로 **운영 환경에서도 정합성이 유지될 수 있는 수준**으로 마무리했습니다.
