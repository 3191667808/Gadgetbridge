#!/usr/bin/env node
/**
 * End-to-end test that mirrors what the Kotlin FitQuestApiClient
 * does, against a live FitQuest server. We do this in JS because
 * we can't run Android instrumentation on this dev box (no emulator,
 * no ADB), but the wire protocol is identical: same endpoints,
 * same cookie semantics, same JSON shapes.
 *
 * Two modes:
 *
 *   1. Full login flow:
 *        $ node PR_FITQUEST_LIVE_TEST.mjs http://localhost:3001 admin fitquest /path/to/fit-dir
 *
 *   2. Cookie-only mode (when you already have a valid session and
 *      just want to verify the upload + logout paths):
 *        $ FITQUEST_COOKIE=s%3A... node PR_FITQUEST_LIVE_TEST.mjs http://localhost:3001 \
 *            --cookie-only /path/to/fit-dir
 *
 * What it verifies:
 *   1. POST /auth/login returns 200 + Set-Cookie with fitquest_session
 *   2. The cookie is accepted by a follow-up request (GET /auth/me)
 *   3. POST /import with the cookie + a real .fit body returns 200
 *      and parses into { files: [{ fitKind, created: [...], ... }]}
 *   4. POST /auth/logout succeeds (best-effort: 200 or 204)
 *   5. After logout, GET /auth/me with the old cookie returns 401
 *      (server-side session row was actually deleted)
 *
 * Exit code: 0 on full pass, 1 on any failure.
 */

import { readFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';

const ARGS = process.argv.slice(2);
const COOKIE_ONLY = ARGS.includes('--cookie-only');
const FLAGS = new Set(ARGS.filter(a => a.startsWith('--')));
const POS = ARGS.filter(a => !a.startsWith('--'));
const BASE = POS[0] ?? 'http://localhost:3001';
const USERNAME = POS[1] ?? 'admin';
const PASSWORD = POS[2] ?? 'fitquest';
const FIT_DIR = POS[COOKIE_ONLY ? 1 : 3] ?? '/tmp/gadgetbridge/ACTIVITY/2026';
const PRECOOKIED = process.env.FITQUEST_COOKIE ?? null;

let failures = 0;
function pass(label) { console.log(`  \u2713 ${label}`); }
function fail(label, msg) { console.log(`  \u2717 ${label} — ${msg}`); failures++; }

// Use Node's built-in fetch (Node 18+) + cookie jar tracking
class Session {
  constructor() { this.cookie = null; }
  setFromSetCookie(setCookieHeader) {
    if (!setCookieHeader) return;
    // "fitquest_session=<value>; Path=/; HttpOnly; ..." — grab first
    // name=value pair.
    const first = setCookieHeader.split(';')[0].trim();
    const eq = first.indexOf('=');
    if (eq > 0) this.cookie = first.substring(eq + 1);
  }
  authHeader() {
    return this.cookie ? { Cookie: `fitquest_session=${this.cookie}` } : {};
  }
}

async function main() {
  console.log(`FitQuest live test against ${BASE}`);
  console.log(`  fit dir: ${FIT_DIR}`);
  console.log(`  mode: ${COOKIE_ONLY ? 'cookie-only' : 'full login'}`);
  console.log();

  const session = new Session();

  if (COOKIE_ONLY) {
    // Step 1+2: skipped — user supplied a cookie via FITQUEST_COOKIE
    if (!PRECOOKIED) {
      fail('--cookie-only requires FITQUEST_COOKIE env var', 'no cookie provided');
      process.exit(1);
    }
    session.cookie = PRECOOKIED;
    console.log('Step 1+2: SKIPPED (using FITQUEST_COOKIE)');
    console.log('\nStep 2b: GET /auth/me with provided cookie');
    const meRes = await fetch(`${BASE}/auth/me`, { headers: session.authHeader() });
    if (meRes.status !== 200) {
      fail('me with cookie = 200', `got ${meRes.status}`);
      process.exit(1);
    }
    const meBody = await meRes.text();
    if (!meBody.includes('"username"')) {
      fail('me response has username', `body: ${meBody.slice(0, 200)}`);
      process.exit(1);
    }
    pass('me with provided cookie = 200 + username');
  } else {
    // --- 1. Login ---
    console.log(`Step 1: POST /auth/login as ${USERNAME}`);
    const loginRes = await fetch(`${BASE}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ identifier: USERNAME, password: PASSWORD }),
    });
    if (loginRes.status !== 200) {
      fail('login status 200', `got ${loginRes.status}`);
      process.exit(1);
    }
    pass('login returned 200');
    const setCookies = loginRes.headers.getSetCookie?.() ?? [loginRes.headers.get('set-cookie')];
    for (const c of setCookies) {
      if (c?.startsWith('fitquest_session=')) {
        session.setFromSetCookie(c);
        break;
      }
    }
    if (!session.cookie) {
      fail('Set-Cookie contains fitquest_session', `headers: ${JSON.stringify([...loginRes.headers])}`);
      process.exit(1);
    }
    pass(`Set-Cookie captured (${session.cookie.length} chars, ${session.cookie.startsWith('s%3A') || session.cookie.includes('.') ? 'signed' : 'plain'})`);

    // --- 2. /auth/me with the cookie ---
    console.log('\nStep 2: GET /auth/me with cookie');
    const meRes = await fetch(`${BASE}/auth/me`, { headers: session.authHeader() });
    if (meRes.status !== 200) {
      fail('me with cookie = 200', `got ${meRes.status}`);
      process.exit(1);
    }
    const meBody = await meRes.text();
    if (!meBody.includes('"username"')) {
      fail('me response has username', `body: ${meBody.slice(0, 200)}`);
      process.exit(1);
    }
    pass('me with cookie = 200 + username');
  }

  // --- 3. Upload one real .fit ---
  console.log('\nStep 3: POST /import with a real .fit');
  const fitFiles = await collectFitFiles(FIT_DIR, 5);
  if (fitFiles.length === 0) {
    fail('find .fit files', `none in ${FIT_DIR}`);
    process.exit(1);
  }
  pass(`collected ${fitFiles.length} fit files for upload`);

  let totalCreated = 0;
  let totalSkipped = 0;
  let totalErrors = 0;
  for (const f of fitFiles) {
    const buf = await readFile(f);
    const uploadRes = await fetch(`${BASE}/import`, {
      method: 'POST',
      headers: {
        ...session.authHeader(),
        'Content-Type': 'application/octet-stream',
      },
      body: buf,
    });
    if (uploadRes.status !== 200) {
      const text = await uploadRes.text();
      fail(`upload ${f.split('/').pop()} status 200`, `got ${uploadRes.status}: ${text.slice(0, 200)}`);
      totalErrors++;
      continue;
    }
    const body = await uploadRes.json();
    const file = body.files?.[0];
    if (!file) {
      fail(`upload ${f.split('/').pop()} has files[0]`, `body: ${JSON.stringify(body).slice(0, 200)}`);
      totalErrors++;
      continue;
    }
    const createdCount = file.created?.length ?? 0;
    const skippedCount = file.skipped?.length ?? 0;
    totalCreated += createdCount;
    totalSkipped += skippedCount;
    pass(`upload ${f.split('/').pop()} → fitKind=${file.fitKind} created=${createdCount} skipped=${skippedCount}`);
  }
  if (totalErrors > 0) {
    fail('no upload errors', `${totalErrors} uploads failed`);
  } else if (totalCreated === 0) {
    fail('uploaded at least one row', `all ${fitFiles.length} files skipped`);
  } else {
    pass(`total created=${totalCreated}, skipped=${totalSkipped}`);
  }

  // --- 4. Logout ---
  console.log('\nStep 4: POST /auth/logout');
  const logoutRes = await fetch(`${BASE}/auth/logout`, {
    method: 'POST',
    headers: { ...session.authHeader(), 'Content-Type': 'application/json' },
    body: '{}',
  });
  if (![200, 204, 302].includes(logoutRes.status)) {
    fail('logout ok status', `got ${logoutRes.status}`);
    process.exit(1);
  }
  pass(`logout returned ${logoutRes.status}`);

  // --- 5. /auth/me with the old cookie now fails ---
  console.log('\nStep 5: GET /auth/me with old cookie (should 401)');
  const meAfterRes = await fetch(`${BASE}/auth/me`, { headers: session.authHeader() });
  if (meAfterRes.status !== 401) {
    fail('me with logged-out cookie = 401', `got ${meAfterRes.status}`);
    process.exit(1);
  }
  pass('me with logged-out cookie = 401');

  console.log();
  if (failures === 0) {
    console.log('OK — all steps passed.');
    process.exit(0);
  } else {
    console.log(`FAIL — ${failures} step(s) failed.`);
    process.exit(1);
  }
}

async function collectFitFiles(dir, limit) {
  const out = [];
  try {
    const entries = await readdir(dir);
    for (const e of entries) {
      if (out.length >= limit) break;
      if (e.endsWith('.fit')) out.push(join(dir, e));
    }
  } catch (e) {
    // ignore
  }
  return out;
}

main().catch((e) => {
  console.error('UNCAUGHT:', e);
  process.exit(2);
});