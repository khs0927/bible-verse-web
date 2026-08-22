import { webkit } from "playwright";

const browser = await webkit.launch();
const page = await browser.newPage({ viewport: { width: 390, height: 844 } });

page.on("console", (message) => console.log(`[browser:${message.type()}] ${message.text()}`));
page.on("pageerror", (error) => console.error(`[browser:pageerror] ${error.message}`));
page.on("requestfailed", (request) => {
  console.error(`[browser:requestfailed] ${request.failure()?.errorText ?? "failed"} ${request.url()}`);
});

try {
  await page.goto("http://127.0.0.1:4173/", { waitUntil: "domcontentloaded", timeout: 30_000 });
  await page.waitForFunction(
    () => document.documentElement.dataset.voiceLabRuntime === "ready",
    undefined,
    { timeout: 15_000 },
  );

  const button = page.getByRole("button", { name: "3A 한국어 토큰화 검증" });
  await button.click();

  await page.waitForFunction(
    () => ["pass", "fail"].includes(document.documentElement.dataset.voiceLabGate3a ?? ""),
    undefined,
    { timeout: 60_000 },
  );

  const state = await page.locator("html").getAttribute("data-voice-lab-gate3a");
  const tokenCountRaw = await page.locator("html").getAttribute("data-voice-lab-gate3a-token-count");
  const details = await page.locator("#gate3a-details").innerText();
  const tokenCount = Number(tokenCountRaw ?? 0);

  console.log(`[voice-lab] Gate 3A state=${state} tokenCount=${tokenCount}`);
  console.log(`[voice-lab] Gate 3A details=${details}`);

  if (state !== "pass") throw new Error(`Gate 3A failed: ${details}`);
  if (!Number.isInteger(tokenCount) || tokenCount <= 0) {
    throw new Error(`Gate 3A returned invalid token count: ${tokenCountRaw}`);
  }
  if (!details.includes("3A PASS")) throw new Error(`Unexpected Gate 3A details: ${details}`);
} finally {
  await browser.close();
}
