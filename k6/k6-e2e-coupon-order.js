// language: javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { randomIntBetween, uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:7777';
const RATE = Number(__ENV.RATE || '10');

const MAX_FAIL_LOGS = Number(__ENV.MAX_FAIL_LOGS || '10');
let failLogs = 0;

export const options = {
  scenarios: {
    e2e_rate: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      preAllocatedVUs: Number(__ENV.PRE_VUS || '20'),
      maxVUs: Number(__ENV.MAX_VUS || '200'),
    },
  },
  thresholds: {
    // NOTE: http_req_failed는 4xx도 실패로 잡히기 쉬워서(기대응답이 아니면),
    // 쿠폰 claim에서 409/410을 정상으로 보는 경우 이 수치는 올라갈 수 있습니다.
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<2000'],
  },
};

export function setup() {
  const walletUserCount = Number(__ENV.WALLET_USER_COUNT || '20000');
  const walletInitialBalance = __ENV.WALLET_INITIAL_BALANCE || '1000000';

  const resetUrl =
    `${BASE_URL}/internal/perf/reset-seed` +
    `?productCount=1000&hotProductCount=20` +
    `&walletUserCount=${walletUserCount}` +
    `&hotCouponQuantity=20000` +
    `&walletInitialBalance=${walletInitialBalance}`;

  const resetRes = http.post(resetUrl, null);

  if (resetRes.status !== 200) {
    console.log(`reset-seed failed status=${resetRes.status} body=${resetRes.body}`);
    throw new Error('reset-seed 호출 실패');
  }

  const hotCouponId = resetRes.json('hotCouponId');
  if (!hotCouponId) {
    console.log(`reset-seed response body=${resetRes.body}`);
    throw new Error('reset-seed 응답에서 hotCouponId를 얻지 못했습니다.');
  }

  return { hotCouponId };
}

function claimCouponOnce(couponId, userId) {
  const claimUrl = `${BASE_URL}/api/coupons/claim/${couponId}`;
  const payload = JSON.stringify({ userId, couponId, requestId: uuidv4() });

  return http.post(claimUrl, payload, {
    headers: { 'Content-Type': 'application/json' },
    timeout: '10s',
  });
}

export default function (data) {
  const couponId = data.hotCouponId;

  const walletUserCount = Number(__ENV.WALLET_USER_COUNT || '20000');
  const userId = randomIntBetween(1, walletUserCount);

  let claimRes = null;
  let userCouponId = null;

  for (let attempt = 1; attempt <= 3; attempt++) {
    claimRes = claimCouponOnce(couponId, userId);

    // 발급 성공일 때만 userCouponId를 추출하고 주문 단계로 진행
    if (claimRes.status === 200) {
      userCouponId = claimRes.json('userCouponId');
      break;
    }

    // 409(이미 발급), 410(소진)은 정상 흐름으로 종료
    if (claimRes.status === 409 || claimRes.status === 410) break;

    if (failLogs < MAX_FAIL_LOGS) {
      failLogs++;
      console.log(
        `claim unexpected status=${claimRes.status} attempt=${attempt} body=${String(claimRes.body).slice(0, 500)}`
      );
    }

    sleep(0.1 * attempt);
  }

  // claim은 200/409/410을 모두 "정상 응답"으로 간주
  const claimOk = check(claimRes, {
    'claim is 200/409/410': (r) => r && (r.status === 200 || r.status === 409 || r.status === 410),
  });

  // 200이 아니면 주문을 진행하지 않음
  if (!claimOk || claimRes.status !== 200) return;

  const hasUserCouponId = check(null, {
    'claim has userCouponId': () => userCouponId !== null && userCouponId !== undefined,
  });
  if (!hasUserCouponId) return;

  const orderUrl = `${BASE_URL}/api/v1/orders`;
  const productId = Math.random() < 0.5 ? randomIntBetween(1, 20) : randomIntBetween(21, 1000);

  const orderPayload = JSON.stringify({
    userId,
    items: [{ productId, quantity: 1 }],
    userCouponId,
    idempotencyKey: uuidv4(),
  });

  const orderRes = http.post(orderUrl, orderPayload, {
    headers: { 'Content-Type': 'application/json' },
    timeout: '10s',
  });

  const ok = check(orderRes, {
    'order is 201 or 409(insufficient balance)': (r) => r.status === 201 || r.status === 409,
  });

  if (!ok && failLogs < MAX_FAIL_LOGS) {
    failLogs++;
    console.log(`order failed status=${orderRes.status} body=${orderRes.body}`);
  }
}