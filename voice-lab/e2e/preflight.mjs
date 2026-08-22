import { webkit } from "playwright";

const browser = await webkit.launch();
const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
const failures = [];

page.on("console", (message) => {
  console.log(`[browser:${message.type()}] ${message.text()}`);
});
page.on("pageerror", (error) => {
  failures.push(`pageerror: ${error.message}`);
  console.error(`[browser:pageerror] ${error.message}`);
});
page.on("requestfailed", (request) => {
  const message = `${request.failure()?.errorText ?? "request failed"} ${request.url()}`;
  failures.push(`requestfailed: ${message}`);
  console.error(`[browser:requestfailed] ${message}`);
});
page.on("response", (response) => {
  if (!response.ok()) {
    const message = `${response.status()} ${response.url()}`;
    failures.push(`http: ${message}`);
    console.error(`[browser:http] ${message}`);
  }
});

async function snapshot(label) {
  const status = await page.locator("#status").textContent().catch(() => null);
  const details = await page.locator("#details").textContent().catch(() => null);
  const buttonCount = await page.locator("#run-preflight").count();
  const buttonDisabled = buttonCount
    ? await page.locator("#run-preflight").isDisabled().catch(() => null)
    : null;
  const runtime = await page.locator("html").getAttribute("data-voice-lab-runtime").catch(() => null);
  const gate = await page.locator("html").getAttribute("data-voice-lab-gate1").catch(() => null);

  console.log(`[voice-lab:${label}] status=${JSON.stringify(status)}`);
  console.log(`[voice-lab:${label}] details=${JSON.stringify(details)}`);
  console.log(`[voice-lab:${label}] buttonCount=${buttonCount} disabled=${buttonDisabled}`);
  console.log(`[voice-lab:${label}] runtime=${runtime} gate1=${gate}`);
}

try {
  await page.goto("http://127.0.0.1:4173/", { waitUntil: "domcontentloaded", timeout: 30_000 });
  await page.waitForTimeout(1000);
  await snapshot("loaded");

  await page.waitForFunction(
    () => document.documentElement.dataset.voiceLabRuntime === "ready",
    undefined,
    { timeout: 10_000 },
  );

  await page.getByRole("button", { name: "1단계 사전검증 시작" }).click();
  await snapshot("clicked");

  await page.waitForFunction(
    () => {
      const gate = document.documentElement.dataset.voiceLabGate1;
      const text = document.querySelector("#status")?.textContent ?? "";
      return gate === "pass" || gate === "fail" || text.includes("실패");
    },
    undefined,
    { timeout: 45_000 },
  );

  await snapshot("finished");

  const gate = await page.locator("html").getAttribute("data-voice-lab-gate1");
  const status = await page.locator("#status").innerText();
  const details = await page.locator("#details").innerText();

  if (gate !== "pass" || !status.includes("3/3 통과")) {
    throw new Error(`Gate 1 failed: ${status} / ${details}`);
  }

  console.log(`[voice-lab] PASS ${status}`);
  console.log(`[voice-lab] ${details}`);
} catch (error) {
  await snapshot("error").catch(() => {});
  console.error(`[voice-lab] captured failures=${JSON.stringify(failures, null, 2)}`);
  console.error(`[voice-lab] final url=${page.url()}`);
  throw error;
} finally {
  await browser.close();
}
