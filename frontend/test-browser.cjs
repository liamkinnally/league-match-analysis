/* eslint-disable @typescript-eslint/no-require-imports -- Shared with CommonJS Playwright configurations. */
const { execFileSync } = require("node:child_process");
const { createHash } = require("node:crypto");
const { createReadStream, existsSync } = require("node:fs");
const { mkdir, mkdtemp, rename, rm } = require("node:fs/promises");
const { join } = require("node:path");

const { browserVersion, chromeHashes, visualShellHashes } = require("./test-browser-pins.json");
const platform = {
  "darwin-arm64": "mac-arm64", "darwin-x64": "mac-x64",
  "linux-arm64": "linux-arm64", "linux-x64": "linux64",
  "win32-x64": "win64", "win32-arm64": "win64", "win32-ia32": "win32",
}[`${process.platform}-${process.arch}`];
if (!platform) throw new Error(`No test browser for ${process.platform}/${process.arch}`);

const cache = join(__dirname, "node_modules", ".cache", "test-browser", browserVersion);
// The Linux visual baselines were captured with Headless Shell. Host tests also support headed Chrome.
const visualShell = process.env.LEAGUE_VISUAL_CONTAINER === "1";
const artifact = visualShell ? "chrome-headless-shell" : "chrome";
const expectedHash = (visualShell ? visualShellHashes : chromeHashes)[platform];
if (!expectedHash) throw new Error(`No pinned ${artifact} for ${platform}`);
const folder = `${artifact}-${platform}`;
const target = join(cache, folder);
const binary = {
  darwin: "Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing",
  linux: artifact, win32: "chrome.exe",
}[process.platform];
const executablePath = join(target, binary);

async function install() {
  if (existsSync(executablePath)) return;
  await mkdir(cache, { recursive: true });
  const stage = await mkdtemp(join(cache, "install-"));
  const archive = join(stage, "browser.zip");
  try {
    const url = `https://storage.googleapis.com/chrome-for-testing-public/${browserVersion}/${platform}/${folder}.zip`;
    const downloadArgs = [
      "--fail", "--silent", "--show-error", "--location",
      "--proto", "=https", "--proto-redir", "=https", "--retry", "2",
      "--output", archive, url,
    ];
    execFileSync("curl", downloadArgs, { stdio: "inherit" });
    const hash = createHash("sha256");
    for await (const chunk of createReadStream(archive)) hash.update(chunk);
    if (hash.digest("hex") !== expectedHash) {
      throw new Error("Chrome for Testing archive failed SHA-256 verification");
    }

    if (process.platform === "win32") {
      execFileSync("tar.exe", ["-xf", archive, "-C", stage]);
    } else {
      execFileSync("unzip", ["-q", archive, "-d", stage]);
    }
    if (!existsSync(join(stage, folder, binary))) throw new Error("Chrome executable missing from verified archive");
    try {
      await rename(join(stage, folder), target);
    } catch (error) {
      // Another installer may have published the same complete, verified directory.
      if (!existsSync(executablePath)) throw error;
    }
  } finally {
    await rm(stage, { recursive: true, force: true });
  }
}

async function main() {
  if (process.argv[2] === "install") {
    await install();
    console.log(`Chrome for Testing ${browserVersion}: ${executablePath}`);
  } else if (process.argv[2] === "path") {
    console.log(executablePath);
  } else {
    throw new Error("Usage: node test-browser.cjs install|path");
  }
}

module.exports = { browserVersion, executablePath };

if (require.main === module) {
  main().catch(error => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
