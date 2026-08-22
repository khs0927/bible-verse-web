import { webkit } from "playwright";

const browser = await webkit.launch();
const page = await browser.newPage({ viewport: { width: 390, height: 844 } });

page.on("console", (message) => console.log(`[browser:${message.type()}] ${message.text()}`));
page.on("pageerror", (error) => console.error(`[browser:pageerror] ${error.message}`));
page.on("requestfailed", (request) => {
  console.error(`[browser:requestfailed] ${request.failure()?.errorText ?? "failed"} ${request.url()}`);
});

async function dump(label) {
  const gate1 = await page.locator("html").getAttribute("data-voice-lab-gate1").catch(() => null);
  const gate2a = await page.locator("html").getAttribute("data-voice-lab-gate2a").catch(() => null);
  const gate2b = await page.locator("html").getAttribute("data-voice-lab-gate2b").catch(() => null);
  const gate2c = await page.locator("html").getAttribute("data-voice-lab-gate2c").catch(() => null);
  const gate2bDetails = await page.locator("#gate2b-details").textContent().catch(() => null);
  const gate2cDetails = await page.locator("#gate2c-details").textContent().catch(() => null);
  const localBadge = await page.locator("#local-session-result").textContent().catch(() => null);
  const codecBadge = await page.locator("#codec-session-result").textContent().catch(() => null);

  console.log(
    `[voice-lab:${label}] gate1=${gate1} gate2a=${gate2a} gate2b=${gate2b} gate2c=${gate2c}`,
  );
  console.log(`[voice-lab:${label}] gate2b-details=${JSON.stringify(gate2bDetails)}`);
  console.log(`[voice-lab:${label}] gate2c-details=${JSON.stringify(gate2cDetails)}`);
  console.log(`[voice-lab:${label}] local=${localBadge} codec=${codecBadge}`);
}

try {
  await page.goto("http://127.0.0.1:4173/", { waitUntil: "domcontentloaded", timeout: 30_000 });
  await page.waitForFunction(
    () => document.documentElement.dataset.voiceLabRuntime === "ready",
    undefined,
    { timeout: 10_000 },
  );

  await page.getByRole("button", { name: "1단계 사전검증 시작" }).click();
  await page.waitForFunction(
    () => document.documentElement.dataset.voiceLabGate1 === "pass",
    undefined,
    { timeout: 45_000 },
  );

  await page.getByRole("button", { name: "2A 모델 크기 검증 시작" }).click();
  await page.waitForFunction(
    () => document.documentElement.dataset.voiceLabGate2a === "pass",
    undefined,
    { timeout: 60_000 },
  );

  const sessionButton = page.getByRole("button", { name: "2B 실제 세션 로드 시작" });
  if (await sessionButton.isDisabled()) throw new Error("Gate 2B button is disabled");

  await sessionButton.click();
  await page.waitForFunction(
    () => ["pass", "fail"].includes(document.documentElement.dataset.voiceLabGate2b ?? ""),
    undefined,
    { timeout: 180_000 },
  );

  const gate2b = await page.locator("html").getAttribute("data-voice-lab-gate2b");
  const gate2bDetails = await page.locator("#gate2b-details").innerText();
  if (gate2b !== "pass") throw new Error(`Gate 2B failed: ${gate2bDetails}`);
  if (!gate2bDetails.includes("2B PASS")) {
    throw new Error(`Unexpected Gate 2B details: ${gate2bDetails}`);
  }

  const coreButton = page.getByRole("button", { name: "2C 핵심 세션 추가 로드" });
  if (await coreButton.isDisabled()) throw new Error("Gate 2C button is disabled after Gate 2B PASS");

  await coreButton.click();
  await page.waitForFunction(
    () => ["pass", "fail"].includes(document.documentElement.dataset.voiceLabGate2c ?? ""),
    undefined,
    { timeout: 240_000 },
  );

  await dump("finished");

  const gate2c = await page.locator("html").getAttribute("data-voice-lab-gate2c");
  const gate2cDetails = await page.locator("#gate2c-details").innerText();
  const localBadge = await page.locator("#local-session-result").innerText();
  const codecBadge = await page.locator("#codec-session-result").innerText();

  if (gate2c !== "pass") throw new Error(`Gate 2C failed: ${gate2cDetails}`);
  if (localBadge !== "PASS" || codecBadge !== "PASS") {
    throw new Error(`Gate 2C badges unexpected: local=${localBadge}, codec=${codecBadge}`);
  }
  if (!gate2cDetails.includes("2C PASS")) {
    throw new Error(`Unexpected Gate 2C details: ${gate2cDetails}`);
  }

  console.log(`[voice-lab] Gate 2B PASS: ${gate2bDetails}`);
  console.log(`[voice-lab] Gate 2C PASS: ${gate2cDetails}`);
} catch (error) {
  await dump("error").catch(() => {});
  throw error;
} finally {
  await browser.close();
}
