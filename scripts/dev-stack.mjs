import { copyFile, access, readFile } from 'node:fs/promises';
import { constants } from 'node:fs';
import { join } from 'node:path';
import { parseEnv } from 'node:util';
import { localConfig, readRiotKey, repository } from './lib/dev-config.cjs';
import { Supervisor, assertPortAvailable } from './lib/supervisor.mjs';

const operation = process.argv[2] ?? 'live';
const mode = operation === 'seed' ? 'app' : operation;
const supervisor = new Supervisor();
let interrupted = false;
let postgresStarted = false;
let config;

async function run(command, args, env, cwd, capture = false) {
  const child = supervisor.start(command, args, {
    env, cwd, ...(capture ? { stdio: ['ignore', 'pipe', 'inherit'] } : {}),
  });
  let output = '';
  if (capture) child.stdout.on('data', chunk => { output += chunk; });
  await supervisor.wait(child);
  supervisor.children.delete(child);
  return output.trim();
}

const composeArgs = args => ['compose', '--env-file', join(repository, '.env.example'), ...args];

async function ensureEnvironment() {
  for (const directory of [repository, join(repository, 'frontend')]) {
    const target = join(directory, directory === repository ? '.env' : '.env.local');
    try { await copyFile(join(directory, '.env.example'), target, constants.COPYFILE_EXCL); }
    catch (error) { if (error.code !== 'EEXIST') throw error; }
  }
  try { await access(join(repository, 'frontend/node_modules/next/package.json')); }
  catch { throw new Error('Frontend dependencies are missing. Run ./scripts/setup once.'); }
  // Next loads its own dotenv files, so reject misplaced keys before starting it.
  for (const file of ['.env', '.env.local', '.env.development', '.env.development.local']) {
    let content;
    try { content = await readFile(join(repository, 'frontend', file), 'utf8'); }
    catch (error) { if (error.code === 'ENOENT') continue; throw error; }
    if (Object.entries(parseEnv(content)).some(([name, value]) => /RIOT.*KEY/i.test(name) && value.trim())) {
      throw new Error('Remove Riot API keys from frontend environment files; keep RIOT_API_KEY only in the ignored root .env.');
    }
  }
}

async function main() {
  config = localConfig(mode);
  if (process.env.DEV_DRY_RUN === '1') {
    console.log(`${mode}: ${config.frontendOrigin} -> ${config.backendOrigin}; PostgreSQL 127.0.0.1:${config.postgresPort}`);
    console.log(`Compose project: ${config.databaseEnv.COMPOSE_PROJECT_NAME}; frontend build: ${config.frontendEnv.NEXT_DIST_DIR}`);
    console.log(mode === 'live' ? 'Riot: enabled for backend only, key from ignored root .env' : 'Riot: offline; RIOT_API_KEY= RIOT_PUBLIC_LOOKUP_ENABLED=false');
    return;
  }
  await ensureEnvironment();
  if (mode === 'live') {
    config.backendEnv.RIOT_API_KEY = await readRiotKey();
    config.backendEnv.RIOT_PUBLIC_LOOKUP_ENABLED = 'true';
  }
  await run('docker', ['info', '--format', '{{.OSType}}'], config.composeEnv, repository, true);
  if (operation !== 'seed') {
    await assertPortAvailable(config.frontendPort);
    await assertPortAvailable(config.backendPort);
  }
  const running = (await run('docker', composeArgs(['ps', '--status', 'running', '--services']), config.composeEnv, repository, true)).split('\n').includes('postgres');
  if (running) {
    const published = await run('docker', composeArgs(['port', 'postgres', '5432']), config.composeEnv, repository, true);
    if (published !== `127.0.0.1:${config.postgresPort}`) {
      throw new Error('This local database is already running on a different port. Stop it before changing DEV_POSTGRES_PORT.');
    }
  } else {
    await assertPortAvailable(config.postgresPort);
    postgresStarted = true;
  }
  await run('docker', composeArgs(['up', '-d', '--wait', 'postgres']), config.composeEnv, repository);
  const backendDir = join(repository, 'backend');
  const args = ['-B', '-ntp', 'spring-boot:run', `-Dspring-boot.run.profiles=${mode === 'fixture' ? 'local,e2e' : 'local'}`];
  if (operation === 'seed') {
    args.push('-Dspring-boot.run.jvmArguments=-Dspring.main.web-application-type=none', '-Dspring-boot.run.arguments=--seed-demo');
    await run('./mvnw', args, config.backendEnv, backendDir);
    return;
  }
  if (mode === 'fixture') {
    await run('./mvnw', ['-B', '-ntp', 'test-compile'], config.backendEnv, backendDir);
    args.push('-Dspring-boot.run.useTestClasspath=true', '-Dspring-boot.run.additional-classpath-elements=target/test-classes');
  }
  const backend = supervisor.start('./mvnw', args, { cwd: backendDir, env: config.backendEnv });
  await supervisor.ready(`${config.backendOrigin}/actuator/health`, backend);
  const frontend = supervisor.start('npm', ['run', 'dev', '--', '--port', String(config.frontendPort)], {
    cwd: join(repository, 'frontend'), env: config.frontendEnv,
  });
  await Promise.race([
    supervisor.ready(`${config.frontendOrigin}/api/health`, frontend),
    supervisor.wait(backend).then(() => { throw new Error('Backend exited during frontend startup.'); }),
  ]);
  console.log(`\n${mode === 'live' ? 'Live Riot development' : mode === 'fixture' ? 'Offline fixture development' : 'Offline development'} ready: ${config.frontendOrigin}`);
  console.log('Ctrl+C stops the app and PostgreSQL started by this command; local data is kept.');
  await Promise.race([supervisor.wait(backend), supervisor.wait(frontend)]);
  throw new Error('A development service exited unexpectedly. Restart ./scripts/dev after checking its output.');
}

for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
  interrupted = true;
  void supervisor.stop();
});

try { await main(); }
catch (error) {
  if (!interrupted) { console.error(error.message); process.exitCode = 1; }
} finally {
  await supervisor.stop();
  if (postgresStarted && config) {
    // Use a fresh supervisor because startup has already been stopped.
    const cleanup = new Supervisor();
    const child = cleanup.start('docker', composeArgs(['stop', 'postgres']), { cwd: repository, env: config.composeEnv });
    try { await cleanup.wait(child); }
    catch { console.error('PostgreSQL could not be stopped. The local data volume was retained.'); process.exitCode = 1; }
  }
}
