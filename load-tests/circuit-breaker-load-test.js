import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { PAUSE, postJson, safeJson, prepareTestData } from './lib/common.js';

const completed = new Counter('transfers_completed');
const failed = new Counter('transfers_failed');

// Bu test "geçti/kaldı" değil, gözlem testi: eşik yok.
// 4 dakika, servisi durdurup başlatmak ve toparlanmayı izlemek için yeterli süre.
export const options = {
    vus: 1,
    duration: '4m',
};

export function setup() {
    const data = prepareTestData('cb');
    data.startedAt = Date.now();
    return data;
}

// Süreye bakarak circuit breaker'ın hangi evrede olduğuna dair ipucu verir.
// Yavaş hata: 3 deneme + aralarındaki 500ms beklemeler (circuit CLOSED veya HALF-OPEN).
// Hızlı hata: çağrı hiç yapılmadan reddedildi (circuit OPEN).
function hint(ms) {
    if (ms >= 900) return ' <- yavas: 3 deneme + bekleme (CLOSED / HALF-OPEN)';
    if (ms < 300) return ' <- hizli red (circuit OPEN)';
    return '';
}

export default function (data) {
    const res = postJson(
        '/api/transactions/transfer',
        {
            senderAccountId: data.senderAccountId,
            receiverAccountId: data.receiverAccountId,
            amount: 10,
        },
        data.token,
        { 'Idempotency-Key': `cb-${data.runId}-${__ITER}` },
        'transfer'
    );

    const elapsed = Math.round((Date.now() - data.startedAt) / 1000);
    const ms = Math.round(res.timings.duration);
    const status = safeJson(res, 'status');

    if (res.status === 200 && status === 'COMPLETED') {
        completed.add(1);
        console.log(`[${elapsed}s] OK    ${ms}ms`);
    } else {
        failed.add(1);
        const reason = safeJson(res, 'failureReason') || String(res.body).slice(0, 100);
        const note = res.status === 200 ? hint(ms) : '';
        console.log(`[${elapsed}s] HATA  ${ms}ms | HTTP ${res.status} | ${status} | ${reason}${note}`);
    }

    sleep(PAUSE);
}