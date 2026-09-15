import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { setTimeout as delay } from 'node:timers/promises';

export async function assertPortAvailable(port) {
  const server = createServer();
  await new Promise((resolve, reject) => {
    server.once('error', error => reject(new Error(error.code === 'EADDRINUSE'
      ? `Port ${port} is already in use. Stop its owner or choose a different DEV_*_PORT.`
      : `Cannot bind local port ${port}: ${error.code}`)));
    server.listen(port, '127.0.0.1', resolve);
  });
  await new Promise(resolve => server.close(resolve));
}

export class Supervisor {
  children = new Map();
  stopping = false;
  constructor({ stopTimeout = 5000 } = {}) { this.stopTimeout = stopTimeout; }

  start(command, args, options = {}) {
    if (this.stopping) throw new Error('Development startup interrupted.');
    const child = spawn(command, args, { detached: true, stdio: 'inherit', ...options });
    const completion = new Promise(resolve => {
      child.once('error', error => resolve({ error }));
      child.once('exit', (code, signal) => resolve({ code, signal }));
    });
    this.children.set(child, completion);
    return child;
  }

  async wait(child) {
    const result = await this.children.get(child);
    if (result.error) throw new Error(`Cannot start process: ${result.error.code}`);
    if (result.code !== 0) throw new Error(`Process exited with ${result.code ?? result.signal}.`);
  }

  async ready(url, child, timeout = 120000) {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      if (this.stopping) throw new Error('Development startup interrupted.');
      const ended = await Promise.race([this.children.get(child), Promise.resolve(null)]);
      if (ended) throw new Error(`Service exited with ${ended.code ?? ended.signal ?? ended.error?.code} before readiness.`);
      try {
        const response = await fetch(url, { signal: AbortSignal.timeout(1000) });
        const body = await response.json();
        if (response.ok && body.status === 'UP') return;
      } catch { /* The server is still starting. */ }
      await delay(Math.min(200, Math.max(1, deadline - Date.now())));
    }
    throw new Error(`Timed out waiting for ${url}. Check the service output above.`);
  }

  async stop() {
    if (this.stopPromise) return this.stopPromise;
    this.stopping = true;
    this.stopPromise = (async () => {
      const signalGroups = signal => {
        for (const child of this.children.keys()) {
          if (child.pid) {
            try { process.kill(-child.pid, signal); }
            catch (error) { if (error.code !== 'ESRCH') throw error; }
          }
        }
      };
      signalGroups('SIGTERM');
      // Groups can outlive the immediate wrapper, so wait for the group, not just its leader.
      if (this.children.size) await delay(this.stopTimeout);
      signalGroups('SIGKILL');
      await Promise.all(this.children.values());
    })();
    return this.stopPromise;
  }
}
