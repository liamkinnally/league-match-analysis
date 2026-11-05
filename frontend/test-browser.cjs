/* eslint-disable @typescript-eslint/no-require-imports -- Shared with CommonJS Playwright configurations. */
const { execFileSync } = require("node:child_process");
const { createHash } = require("node:crypto");
const { createReadStream, existsSync } = require("node:fs");
const { mkdir, mkdtemp, rename, rm } = require("node:fs/promises");
const { join } = require("node:path");

// Hashes of the corresponding official Chrome for Testing archives.
const browserVersion = "157.0.8103.38";
const chromeHashes = {
  "mac-arm64": "3406b38b52b7d341d74450eb7b369be038c9fec9894c8b8a9e9e9f97333caf2f",
  "mac-x64": "c6fcc2869b0d5a421e880d7bcdabe96e4261c4100e125fc4e4daf6346474efa6",
  "linux-arm64": "5517510e61657ff43ee9ab804ecd1a499b3d5bd3caf72f097516bd6565ba5a17",
  linux64: "8cdeb344e9b2cefe59f4aea8d1d93d5d93054c03cde217665c16fc1f62e72a66",
  win32: "be61f767e50b6d94586710afa138535306084b438d9e30bee600a538e16a278a",
  win64: "ae0deef27d449e1d7529df7705d609bab102de48464da13567ed3dd2ee1a0586",
};
const visualShellHashes = {
  linux64: "deafffa74660b34853a4119c361fd427ac8c9fcb62db51ddb8a797cd83a4ba2d",
  "linux-arm64": "b9b910a57b2a15eb3e55000a2339f93a973934a413ebfc6e585a50ec6c42fd6c",
};
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
