const { createHash } = require('node:crypto');
const { readFile } = require('node:fs/promises');
const { resolve, join } = require('node:path');
const { parseEnv } = require('node:util');

const repository = resolve(__dirname, '../..');
exports.repository = repository;

function port(value, fallback) {
  const number = Number(value ?? fallback);
  if (!Number.isInteger(number) || number < 1024 || number > 65535) {
    throw new Error('Local ports must be integers between 1024 and 65535.');
  }
  return number;
}

function localConfig(mode, environment = process.env, repoDir = repository) {
  if (!['live', 'app', 'fixture'].includes(mode)) throw new Error('Unknown development mode.');
  const fixture = mode === 'fixture';
  const frontendPort = port(environment.DEV_FRONTEND_PORT, fixture ? 3100 : 3000);
  const backendPort = port(environment.DEV_BACKEND_PORT, fixture ? 8180 : 8080);
  const postgresPort = port(environment.DEV_POSTGRES_PORT, fixture ? 5549 : 5548);
  if (new Set([frontendPort, backendPort, postgresPort]).size !== 3) {
    throw new Error('Frontend, backend and PostgreSQL need different local ports.');
  }
  const base = { ...environment };
  for (const key of Object.keys(base)) {
    if (/^(RIOT_|SPRING_|POSTGRES_|COMPOSE_|BACKEND_|VERCEL|NEXT_PUBLIC_RIOT_|PLAYER_REMOVAL_LEDGER_FILE$)/.test(key)) delete base[key];
  }
  // Java flags can override Spring's environment-based local database boundary.
  for (const key of ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'MAVEN_OPTS']) delete base[key];
  const suffix = createHash('sha256').update(resolve(repoDir)).digest('hex').slice(0, 8);
  const group = fixture ? 'fixture' : 'dev';
  const database = `league_analysis_${group}`;
  const databaseEnv = {
    POSTGRES_DB: database, POSTGRES_USER: 'league_analysis',
    POSTGRES_PASSWORD: 'local-development-only', POSTGRES_PORT: String(postgresPort),
    COMPOSE_PROJECT_NAME: `league-${group}-${suffix}`,
    COMPOSE_FILE: join(repoDir, 'compose.yaml'),
  };
  const frontendOrigin = `http://127.0.0.1:${frontendPort}`;
  const backendOrigin = `http://127.0.0.1:${backendPort}`;
  const offline = { RIOT_API_KEY: '', RIOT_PUBLIC_LOOKUP_ENABLED: 'false' };
  const backendEnv = {
    ...base, ...databaseEnv, ...offline,
    SPRING_DATASOURCE_URL: `jdbc:postgresql://127.0.0.1:${postgresPort}/${database}`,
    SPRING_DATASOURCE_USERNAME: databaseEnv.POSTGRES_USER,
    SPRING_DATASOURCE_PASSWORD: databaseEnv.POSTGRES_PASSWORD,
    SERVER_PORT: String(backendPort), SERVER_ADDRESS: '127.0.0.1',
    BACKEND_AUTH_REQUIRED: 'false', BACKEND_SERVICE_TOKEN: '', PLAYER_REMOVAL_LEDGER_FILE: '',
  };
  const frontendEnv = {
    ...base, ...offline, NEXT_PUBLIC_RIOT_API_KEY: '',
    BACKEND_URL: backendOrigin, BACKEND_AUTH_REQUIRED: 'false', BACKEND_SERVICE_TOKEN: '',
    LEAGUE_ANALYSIS_RUNTIME: 'local', UI_LAB_ENABLED: '', PREVIEW_DATA_SOURCE: 'backend',
    NEXT_DIST_DIR: fixture ? '.next-fixture' : '.next-local',
  };
  return { mode, repoDir, frontendPort, backendPort, postgresPort, frontendOrigin, backendOrigin,
    databaseEnv, composeEnv: { ...base, ...databaseEnv, ...offline }, backendEnv, frontendEnv };
}

async function readRiotKey(repoDir = repository) {
  let key;
  try { key = parseEnv(await readFile(join(repoDir, '.env'), 'utf8')).RIOT_API_KEY?.trim(); }
  catch { /* Use one actionable error that never contains file contents. */ }
  if (!key || !/^[\x21-\x7e]+$/.test(key)) {
    throw new Error('Set RIOT_API_KEY in the ignored root .env, then run ./scripts/dev live again.');
  }
  return key;
}
exports.localConfig = localConfig;
exports.readRiotKey = readRiotKey;
