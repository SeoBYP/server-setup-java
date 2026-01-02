# Transaction Diagnosis (MSA 분리 설계 + 트랜잭션 한계/해결방안 통합 문서)

## 0. 문서 개요

- **목적**: 현재 단일 애플리케이션에서 한 트랜잭션으로 처리되던 주문/결제/재고/쿠폰/이벤트 발행을, MSA로 분리했을 때의 **트랜잭션 한계**를 진단하고 **해결 가능한 서비스 설계(사가, 아웃박스, 멱등성, DLQ)**를 제시한다.
- **범위(현 프로젝트 기준 도메인)**: 주문(Order), 상품(Product/Stock), 지갑(Wallet), 쿠폰(Coupon/UserCoupon), 인기상품(PopularProduct), 이벤트 전송(Data Platform), Outbox/Worker, Redis(DLQ/Stream).
- **핵심 결론**
    1. 배포 단위는 `Order / Product / Wallet / Coupon / PopularProduct(Analytics)`로 분리한다.
    2. 서비스 간 원자적 커밋 불가 → **Saga(Orchestration)** 로 “예약/확정/보상” 설계.
    3. 이벤트 발행 신뢰성 → **서비스별 Outbox + Relay(Worker)**.
    4. 재시도/중복/네트워크 오류 대응 → **Idempotency(요청/이벤트) + DLQ + 재처리**.

---

## 1. 현 시스템(모놀리식) 트랜잭션 진단

### 1.1 현재 주문 처리의 트랜잭션 특성(진단)
현재는 “주문 생성” 과정에서 다음이 **단일 트랜잭션**으로 묶여 원자성을 보장한다.

- 재고 차감
- 쿠폰 유효성 확인 및 사용 처리(선택)
- 지갑 잔액 차감
- 주문 저장
- Outbox에 `ORDER_CREATED` 이벤트 기록

**장점**
- 실패 시 전체 롤백 가능(재고만 줄고 주문이 없거나, 쿠폰만 사용되는 등 불일치 최소화)

**MSA 분리 시 문제**
- 위 작업이 서로 다른 서비스/DB로 분산 → 단일 트랜잭션으로 묶을 수 없음(분산 트랜잭션 한계)

### 1.2 현재 시스템이 이미 갖춘 MSA 준비 요소(요약)
- **Outbox 패턴**(주문 생성 시 이벤트 레코드 저장)
- **Outbox Worker**(PENDING 이벤트 발행 + 성공/실패 상태 업데이트)
- **DLQ(스트림) 구성 및 재처리 흐름**
- **동시성 테스트**(지갑/쿠폰/주문 경쟁 상황 검증)
- **주문 IdempotencyKey** 기반 중복 방지

➡️ MSA 전환 시, 이 요소들을 “서비스별 표준 패턴”으로 확장하면 된다.

---

## 2. MSA 배포 단위(도메인) 설계

## 2.1 서비스 분리 원칙
- **데이터 소유권**: 각 서비스는 자기 DB만 변경(타 서비스 테이블 직접 접근 금지)
- **통신**: 중요한 상태 변경은 이벤트 기반(최종 일관성), 꼭 필요한 경우만 동기 호출
- **일관성 목표**: 서비스 내부는 강한 일관성, 서비스 간은 최종 일관성 + 보상 트랜잭션

## 2.2 권장 배포 단위(서비스) 목록

### A. Order Service (주문 서비스)
- **책임**: 주문 생성/조회, 주문 상태 관리, 사가 오케스트레이션, idempotencyKey 관리
- **소유 데이터**: Order, OrderItem, OrderStatus, (권장) SagaState/StepLog, (권장) ProcessedCommand
- **발행 이벤트**: `OrderCreated`, `OrderCompleted`, `OrderCanceled`, `OrderFailed`

### B. Product Service (상품/재고 서비스)
- **책임**: 상품 조회, 재고 예약/차감/복구
- **소유 데이터**: Product, Stock(또는 Product.stock), (권장) StockReservation
- **발행 이벤트**: `StockReserved`, `StockReserveFailed`, `StockReleased`

### C. Wallet Service (지갑/결제 서비스)
- **책임**: 잔액 충전/차감, 결제 승인/확정/취소
- **소유 데이터**: Wallet, (권장) WalletTransaction(원장)
- **발행 이벤트**: `WalletAuthorized`, `WalletAuthorizeFailed`, `WalletCaptured`, `WalletCanceled`

### D. Coupon Service (쿠폰 서비스)
- **책임**: 쿠폰 발급/조회, 쿠폰 사용 예약/확정/취소
- **소유 데이터**: Coupon, UserCoupon(상태)
- **발행 이벤트**: `CouponReserved`, `CouponReserveFailed`, `CouponUsed`, `CouponCanceled`

### E. PopularProduct Service (인기상품/집계 서비스; Analytics)
- **책임**: 주문 이벤트 기반 집계 업데이트(판매수량/랭킹)
- **소유 데이터**: PopularProduct 집계, Redis 랭킹 구조, (권장) ProcessedEvent(중복방지)
- **소비 이벤트**: `OrderCreated`(또는 `OrderCompleted`)

---

## 3. 트랜잭션 한계(문제 정의)

MSA 분리 후 “주문 생성”은 다음 서비스들에 걸친 작업이 된다.

- Product DB: 재고 처리
- Wallet DB: 결제 처리
- Coupon DB: 쿠폰 처리(옵션)
- Order DB: 주문 저장 및 상태 전이

### 3.1 대표 실패 시나리오(정합성 깨짐)
1. **재고 예약 성공 → 결제 승인 실패**
    - 재고는 묶였는데 주문 실패 → 재고 복구(ReleaseStock) 필요
2. **결제 승인 성공 → 주문 최종 저장 실패**
    - 돈은 잡혔는데 주문이 없음 → 결제 취소(CancelDebit) 필요
3. **주문 저장 성공 → 이벤트 발행 실패**
    - 소비자(인기상품/외부 전송)가 주문을 모름 → Outbox 기반 재발행 필요
4. **이벤트 중복 전달**
    - 집계/외부전송이 2번 반영 → 소비자 멱등 처리 필요
5. **타임아웃으로 인한 ‘성공했는데 실패로 인지’**
    - 재시도 시 중복 처리 위험 → 커맨드 멱등성 필요

---

## 4. 해결 전략(표준 패턴)

## 4.1 Saga(사가) + 보상 트랜잭션

### 4.1.1 선택: Orchestration Saga(권장)
- **Order Service가 오케스트레이터**가 되어 단계별로 커맨드 호출/이벤트 수신 후 상태를 전이한다.
- 이유: 주문은 비즈니스 플로우의 중심이며, 실패 처리(보상)가 명확하다.

### 4.1.2 주문 사가 상태 모델(예시)
| 상태 | 의미 |
|---|---|
| `PENDING` | 주문 접수/사가 시작 |
| `STOCK_RESERVED` | 재고 예약 성공 |
| `PAYMENT_AUTHORIZED` | 결제 승인(홀드) 성공 |
| `COUPON_RESERVED` | 쿠폰 사용 예약 성공(옵션) |
| `COMPLETED` | 주문 완료(확정) |
| `CANCELED` | 보상 완료로 취소 |
| `FAILED` | 실패(재시도 또는 수동 처리 필요) |

### 4.1.3 단계별 처리(권장 흐름)
1. **CreateOrder 요청 수신 (idempotencyKey 필수)**
    - Order Service는 `PENDING` 주문(또는 Saga 인스턴스) 생성
2. **Product: ReserveStock(orderId, items)**
    - 성공 시 `StockReserved` 이벤트
    - 실패 시 `StockReserveFailed` 이벤트 → Order는 `FAILED`
3. **Wallet: AuthorizeDebit(orderId, userId, amount)**
    - 성공 시 `WalletAuthorized`
    - 실패 시 `WalletAuthorizeFailed` → 보상: Product `ReleaseStock`
4. **Coupon(옵션): ReserveCouponUse(orderId, userCouponId)**
    - 성공 시 `CouponReserved`
    - 실패 시 `CouponReserveFailed` → 보상: Wallet `CancelDebit` + Product `ReleaseStock`
5. **Order 확정(Complete)**
    - Order Service가 `COMPLETED`로 전이 + Outbox에 `OrderCompleted/OrderCreated` 기록
6. **확정 반영(선택적으로 2단계 확정)**
    - Wallet `CaptureDebit`, Coupon `ConfirmUse`, Product `CommitStock`(예약=확정이면 생략 가능)

> 트래픽/재고 민감도가 높다면 “예약 → 확정(2단계)”가 안전합니다. 간단히 가려면 예약=확정으로 하되, 보상 트랜잭션을 더 엄격히 운영해야 합니다.

---

## 4.2 Outbox 패턴(서비스별) + Relay(Worker)

### 4.2.1 목적
- **DB 커밋은 성공했는데 메시지 발행이 실패**하는 문제를 제거(“Dual Write” 문제 해결)
- 각 서비스는 **상태 변경 트랜잭션 내부에서 Outbox 레코드 저장**
- Relay(Worker)가 Outbox를 읽어 브로커로 발행하고 상태를 갱신

### 4.2.2 Outbox 레코드 최소 스키마(권장)
- `id`(eventId), `aggregateType`, `aggregateId`, `eventType`, `payload(json)`,
- `status(PENDING/SENT/FAILED)`, `createdAt`
- (권장) `attemptCount`, `nextRetryAt`, `lastError`

### 4.2.3 발행 신뢰성 정책
- Relay는 `PENDING`을 일정 배치로 조회(예: 100건)
- 성공: `SENT`
- 실패: `FAILED` + 재시도(지수 백오프)
- 오래 실패: DLQ로 이동 + 알람

---

## 4.3 Idempotency(멱등) 설계: 커맨드/이벤트 모두 적용

### 4.3.1 커맨드 멱등(요청 중복 방지)
- Order 생성: `idempotencyKey`로 같은 요청 1회만 생성
- 서비스 간 커맨드(Reserve/Authorize 등)도 **orderId 기반 멱등 키**를 사용
    - 예: Wallet은 `(orderId)` 또는 `(orderId, step)` 단위로 “이미 처리됨”을 판정

### 4.3.2 이벤트 멱등(소비자 중복 방지)
- 소비자는 `eventId`(Outbox id)를 기준으로 **처리 이력 저장**
- PopularProduct처럼 집계는 “한 번만 증가”가 중요하므로 필수

권장 구현(개념)
- `ProcessedEvent(consumerName, eventId)`를 저장하고, 존재하면 skip
- 또는 Redis SET/ZSET을 사용하되, 영속성이 필요하면 DB 병행

---

## 4.4 DLQ(Dead Letter Queue) 및 재처리

### 4.4.1 DLQ 적재 조건(예시)
- 역직렬화 실패(스키마 불일치)
- 외부 전송/DB 반영이 N회 이상 실패
- 비즈니스적으로 처리 불가능한 메시지(필수 필드 누락 등)

### 4.4.2 DLQ 재처리 원칙
- 재처리 Worker가 DLQ를 읽어 재시도
- 성공 시 ACK/삭제
- 재시도 초과 시 “Dead DLQ”로 격리 + 운영 알람(수동 조치)

---

## 5. 통합 플로우(시퀀스) — “주문 생성” End-to-End

## 5.1 정상 시나리오(요약)
1. Client → Order: `POST /orders`(idempotencyKey 포함)
2. Order: 주문 PENDING 저장 + Outbox(사가 시작 이벤트 선택) 기록
3. Order → Product: ReserveStock
4. Product: 예약 성공 → Outbox `StockReserved` → 브로커 발행
5. Order(소비): StockReserved 수신 → Wallet AuthorizeDebit 호출
6. Wallet: 승인 성공 → Outbox `WalletAuthorized` → 발행
7. Order(소비): WalletAuthorized 수신 → (옵션) Coupon ReserveCouponUse
8. Coupon: 예약 성공 → Outbox `CouponReserved` → 발행
9. Order: COMPLETED 전이 + Outbox `OrderCreated/OrderCompleted` 기록 → 발행
10. PopularProduct: OrderCreated(또는 Completed) 소비 → 집계 증가(멱등 처리)

## 5.2 실패 시나리오 예: 결제 실패
- WalletAuthorizeFailed 이벤트 수신 → Order는 보상 실행
    - Product ReleaseStock 호출(또는 ReleaseStock 이벤트 기반)
    - 주문 상태 `CANCELED/FAILED` 전이
    - Outbox로 `OrderFailed` 발행

---

## 6. 서비스별 API 명세(요약)

> 형식은 제출용 간결 버전이며, 실제 운영에선 에러코드/필드/검증을 더 구체화합니다.

## 6.1 Order Service
### `POST /orders`
- Request
    - `userId: number`
    - `items: [{ productId: number, quantity: number }]`
    - `userCouponId?: number`
    - `idempotencyKey: string`
- Response
    - `orderId: number`
    - `status: PENDING|COMPLETED|FAILED|CANCELED`
    - `paidAmount?: number`
- Error(예)
    - `IDEMPOTENCY_KEY_REQUIRED`
    - `ORDER_ITEMS_EMPTY`
    - `INVALID_QUANTITY`

### `GET /orders/{orderId}`
- Response: 주문 상세(상태 포함)

## 6.2 Product Service
### `POST /stocks/reservations`
- Request: `orderId`, `items`
- Response: `reservationId`, `status`
- Error: `INSUFFICIENT_STOCK`, `PRODUCT_NOT_FOUND`

### `POST /stocks/reservations/{reservationId}/release`
- 보상(재고 복구)

## 6.3 Wallet Service
### `POST /wallets/{userId}/debits/authorize`
- Request: `orderId`, `amount`
- Response: `txId`, `AUTHORIZED`
- Error: `INSUFFICIENT_BALANCE`

### `POST /wallets/debits/{txId}/cancel`
- 보상(승인 취소)

### `POST /wallets/debits/{txId}/capture`(선택)
- 확정 차감

## 6.4 Coupon Service
### `POST /coupons/reservations`
- Request: `orderId`, `userCouponId`, `userId`
- Response: `reservationId`, `RESERVED`
- Error: `ALREADY_USED`, `EXPIRED`, `NOT_YET_AVAILABLE`, `ALREADY_CLAIMED`(케이스별)

### `POST /coupons/reservations/{reservationId}/cancel`
- 보상(예약 취소)

### `POST /coupons/reservations/{reservationId}/confirm`(선택)
- 확정 사용(USED)

---

## 7. 이벤트 명세(스키마) — v1 제안

## 7.1 공통 Envelope
- `eventId: number|string` (Outbox PK 권장)
- `eventType: string`
- `schemaVersion: number` (예: 1)
- `aggregateType: string`
- `aggregateId: string`
- `occurredAt: string(ISO-8601)`
- `correlationId: string` (권장: orderId)
- `payload: object|string(JSON)`

## 7.2 주요 이벤트 타입
- Order: `ORDER_CREATED`, `ORDER_COMPLETED`, `ORDER_CANCELED`, `ORDER_FAILED`
- Product: `STOCK_RESERVED`, `STOCK_RESERVE_FAILED`, `STOCK_RELEASED`
- Wallet: `WALLET_AUTHORIZED`, `WALLET_AUTHORIZE_FAILED`, `WALLET_CAPTURED`, `WALLET_CANCELED`
- Coupon: `COUPON_RESERVED`, `COUPON_RESERVE_FAILED`, `COUPON_USED`, `COUPON_CANCELED`

---

## 8. 일관성/격리/동시성 전략

## 8.1 서비스 내부 트랜잭션
- 각 서비스는 자기 DB 변경에 대해 **강한 일관성** 유지
- 재고/지갑/쿠폰은 경합이 크므로
    - 비관적 락(SELECT FOR UPDATE) 또는
    - 낙관적 락(version) + 재시도
    - (선택) 분산락(쿠폰/핫키) 사용 가능

## 8.2 서비스 간 최종 일관성
- 사가 진행 중 상태(PENDING)를 사용자에게 노출할 수 있어야 함
- 조회 API는 상태 기반으로 “처리중/완료/실패”를 반환

---

## 9. 운영 설계(재시도/관측/알람)

- **재시도**
    - Relay(Outbox): FAILED 재시도(백오프)
    - 소비자: 처리 실패 시 재시도 후 DLQ
- **관측성**
    - 모든 로그/이벤트에 `correlationId(orderId)` 포함
- **알람**
    - Outbox FAILED 누적 임계치 초과
    - DLQ 적재량 증가
    - 사가 PENDING 장기 체류(예: 5분 이상)

---

## 10. 제출용 요약(한 문단)

본 설계는 주문/상품/지갑/쿠폰/인기상품 도메인을 MSA로 분리하고, 단일 트랜잭션이 불가능해지는 한계를 Saga(오케스트레이션)로 해결한다. 서비스 내부는 강한 일관성을 유지하되 서비스 간 정합성은 최종 일관성으로 전환하며, 이를 위해 서비스별 Outbox+Relay로 이벤트 발행 신뢰성을 확보하고, 커맨드/이벤트 모두에 멱등성을 적용하며, DLQ 및 재처리 워커로 장애 내성을 갖춘다. 인기상품 집계는 주문 이벤트를 멱등 소비하여 중복 증가를 방지하고, 전체 플로우는 correlationId 기반 추적 및 실패 보상 트랜잭션으로 복구 가능하게 설계한다.

---