import http from 'k6/http';
import { sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Gateway'deki rate limiter 10 saniyede 10 isteğe izin veriyor.
// İstekler arasında bu kadar bekleyerek limitin altında kalıyoruz.
export const PAUSE = 1.2;

export function postJson(path, body, token, extraHeaders, name) {
    const headers = Object.assign({ 'Content-Type': 'application/json' }, extraHeaders);
    if (token) {
        headers['Authorization'] = `Bearer ${token}`;
    }
    return http.post(`${BASE_URL}${path}`, JSON.stringify(body), {
        headers: headers,
        tags: { name: name },
    });
}

export function safeJson(res, selector) {
    try {
        return res.json(selector);
    } catch (e) {
        return null;
    }
}

function mustBeOk(res, step) {
    if (res.status < 200 || res.status >= 300) {
        throw new Error(`Setup adimi basarisiz (${step}): HTTP ${res.status} - ${res.body}`);
    }
    return res;
}

// Yeni bir kullanıcı, müşteri ve iki hesap oluşturur, gönderen hesaba 1.000.000 yatırır.
// Her çalıştırmada kendi verisini kurduğu için tekrar tekrar çalıştırılabilir.
export function prepareTestData(prefix) {
    const runId = `${Date.now()}`;
    const username = `${prefix}${runId.slice(-10)}`;
    const password = 'Test1234!';
    const email = `${username}@example.com`;

    mustBeOk(
        postJson('/api/auth/register', { username, password, email }, null, {}, 'setup-register'),
        'register'
    );
    sleep(PAUSE);

    const loginRes = mustBeOk(
        postJson('/api/auth/login', { username, password }, null, {}, 'setup-login'),
        'login'
    );
    const token = loginRes.json('token');
    sleep(PAUSE);

    const customerRes = mustBeOk(
        postJson(
            '/api/customers',
            {
                firstName: 'Load',
                lastName: 'Test',
                identityNumber: runId.slice(-11),
                email: email,
                phoneNumber: '5550000000',
                address: 'k6',
            },
            token,
            {},
            'setup-customer'
        ),
        'customer'
    );
    const customerId = customerRes.json('id');
    sleep(PAUSE);

    const senderRes = mustBeOk(
        postJson('/api/accounts', { customerId: customerId, currency: 'TRY' }, token, {}, 'setup-account'),
        'sender account'
    );
    const senderAccountId = senderRes.json('id');
    sleep(PAUSE);

    const receiverRes = mustBeOk(
        postJson('/api/accounts', { customerId: customerId, currency: 'TRY' }, token, {}, 'setup-account'),
        'receiver account'
    );
    const receiverAccountId = receiverRes.json('id');
    sleep(PAUSE);

    mustBeOk(
        postJson(`/api/accounts/${senderAccountId}/deposit`, { amount: 1000000 }, token, {}, 'setup-deposit'),
        'deposit'
    );

    console.log(`Setup tamam: kullanici=${username}, gonderen hesap=${senderAccountId}, alici hesap=${receiverAccountId}`);

    return { token, senderAccountId, receiverAccountId, runId };
}