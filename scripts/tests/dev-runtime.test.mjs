import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:net';
import { spawnSync } from 'node:child_process';
import { localConfig, readRiotKey } from '../lib/dev-config.cjs';
import { Supervisor, assertPortAvailable } from '../lib/supervisor.mjs';

test('live and fixture configuration isolate data and strip inherited credentials', () => {
  const inherited = { PATH: process.env.PATH, RIOT_API_KEY: 'dummy-inherited-key',
    SPRING_DATASOURCE_URL: 'jdbc:postgresql://remote/production', POSTGRES_PASSWORD: 'private',
    BACKEND_URL: 'https://production.invalid', BACKEND_SERVICE_TOKEN: 'private',
    NEXT_PUBLIC_RIOT_API_KEY: 'dummy-public-key', COMPOSE_PROJECT_NAME: 'production',
    PREVIEW_DATA_SOURCE: 'sample', VERCEL: '1' };
  const live = localConfig('live', inherited, '/tmp/example');
  const fixture = localConfig('fixture', inherited, '/tmp/example');
  assert.equal(live.frontendOrigin, 'http://127.0.0.1:3000');
  assert.equal(fixture.frontendOrigin, 'http://127.0.0.1:3100');
  assert.notEqual(live.composeEnv.COMPOSE_PROJECT_NAME, fixture.composeEnv.COMPOSE_PROJECT_NAME);
  assert.notEqual(live.backendEnv.SPRING_DATASOURCE_URL, fixture.backendEnv.SPRING_DATASOURCE_URL);
  assert.notEqual(live.frontendEnv.NEXT_DIST_DIR, fixture.frontendEnv.NEXT_DIST_DIR);
  assert.equal(live.frontendEnv.BACKEND_URL, 'http://127.0.0.1:8080');
  assert.equal(live.frontendEnv.RIOT_API_KEY, '');
  assert.equal(live.frontendEnv.NEXT_PUBLIC_RIOT_API_KEY, '');
  assert.equal(live.frontendEnv.POSTGRES_PASSWORD, undefined);
  assert.equal(live.frontendEnv.BACKEND_SERVICE_TOKEN, '');
  assert.equal(fixture.backendEnv.RIOT_API_KEY, '');
  assert.equal(fixture.backendEnv.RIOT_PUBLIC_LOOKUP_ENABLED, 'false');
  assert.ok(!JSON.stringify([live, fixture]).includes('dummy-inherited-key'));
  assert.ok(!JSON.stringify([live, fixture]).includes('remote/production'));
});

test('missing key fails without exposing or executing environment content', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'dev-key-test-'));
  try {
    await assert.rejects(readRiotKey(directory), /RIOT_API_KEY.*ignored.*\.env/);
    await writeFile(join(directory, '.env'), 'RIOT_API_KEY=\n');
    await assert.rejects(readRiotKey(directory), /RIOT_API_KEY.*ignored.*\.env/);
    await writeFile(join(directory, '.env'), 'RIOT_API_KEY="dummy-local-key"\n');
    assert.equal(await readRiotKey(directory), 'dummy-local-key');
  } finally { await rm(directory, { recursive: true }); }
});

test('occupied ports are rejected without disturbing their owner', async () => {
  const server = createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    await assert.rejects(assertPortAvailable(server.address().port), /already in use/);
    assert.equal(server.listening, true);
  } finally { await new Promise(resolve => server.close(resolve)); }
});

test('supervisor stops descendants even after their parent has exited', async () => {
  const supervisor = new Supervisor({ stopTimeout: 200 });
  const child = supervisor.start(process.execPath, ['-e', `
    const { spawn } = require('node:child_process');
    const child = spawn(process.execPath, ['-e', 'setInterval(()=>{}, 1000)'], { stdio: 'ignore' });
    console.log(child.pid); child.unref();
  `], { stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '';
  child.stdout.on('data', chunk => { output += chunk; });
  await supervisor.wait(child);
  const descendant = Number(output.trim());
  assert.ok(descendant > 0);
  process.kill(descendant, 0);
  await supervisor.stop();
  // The process group must be signalled even though the original child exited.
  await new Promise(resolve => setTimeout(resolve, 100));
  assert.throws(() => process.kill(descendant, 0), { code: 'ESRCH' });
});

test('readiness detects early failures and timeouts and cleanup is idempotent', async () => {
  const supervisor = new Supervisor({ stopTimeout: 200 });
  const failed = supervisor.start(process.execPath, ['-e', 'process.exit(7)'], { stdio: 'ignore' });
  await assert.rejects(supervisor.ready('http://127.0.0.1:1', failed, 1000), /exited.*7/);
  const idle = supervisor.start(process.execPath, ['-e', 'setInterval(()=>{}, 1000)'], { stdio: 'ignore' });
  await assert.rejects(supervisor.ready('http://127.0.0.1:1', idle, 30), /Timed out/);
  await supervisor.stop();
  await supervisor.stop();
  assert.notEqual(idle.exitCode ?? idle.signalCode, null);
});

test('the real verification entry point clears inherited live credentials', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'offline-verify-test-'));
  try {
    await writeFile(join(directory, 'npm'), '#!/usr/bin/env node\nconsole.log(JSON.stringify([process.env.RIOT_API_KEY, process.env.RIOT_PUBLIC_LOOKUP_ENABLED]));\n', { mode: 0o755 });
    const result = spawnSync('./scripts/verify', ['focused', 'frontend', 'example.test.ts'], {
      cwd: new URL('../../', import.meta.url), encoding: 'utf8',
      env: { ...process.env, PATH: `${directory}:${process.env.PATH}`, RIOT_API_KEY: 'inherited-test-key', RIOT_PUBLIC_LOOKUP_ENABLED: 'true' },
    });
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(JSON.parse(result.stdout), ['', 'false']);
  } finally { await rm(directory, { recursive: true }); }
});
