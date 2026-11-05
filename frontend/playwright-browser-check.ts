import { chromium, type FullConfig } from "@playwright/test";
import { browserVersion } from "./test-browser.cjs";

export default async function checkTestBrowser(config: FullConfig) {
  const browser = await chromium.launch(config.projects[0].use.launchOptions);
  try {
    const actual = browser.version();
    if (actual !== browserVersion) {
      throw new Error(`Expected Chrome for Testing ${browserVersion}, launched ${actual}`);
    }
    console.log(`Browser verification: Chrome for Testing ${actual}`);
  } finally {
    await browser.close();
  }
}
