import { webkit } from "playwright";

const MODEL_META_URL =
  "https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX/resolve/main/tts_browser_onnx_meta.json";

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
  if (response.status() >= 400) {
    const message = `${response.status()} ${response.url()}`;
    failures.push(`http: ${message}`);
    console.error(`[browser:http] ${message}`);
  }
});

async function snapshot(label) {
  const status = await page.locator("#status").textContent().catch(() => null);
  const details = await page.locator("#details").textContent().catch(() => null);
  const runtime = await page.locator("html").getAttribute("data-voice-lab-runtime").catch(() => null);
  const gate1 = await page.locator("html").getAttribute("data-voice-lab-gate1").catch(() => null);
  const gate2a = await page.locator("html").getAttribute("data-voice-lab-gate2a").catch(() => null);
  const gate2Details = await page.locator("#gate2-details").textContent().catch(() => null);

  console.log(`[voice-lab:${label}] status=${JSON.stringify(status)}`);
  console.log(`[voice-lab:${label}] details=${JSON.stringify(details)}`);
  console.log(`[voice-lab:${label}] runtime=${runtime} gate1=${gate1} gate2a=${gate2a}`);
  console.log(`[voice-lab:${label}] gate2=${JSON.stringify(gate2Details)}`);
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

  const nativeFetchProbe = await page.evaluate(async (url) => {
    try {
      const response = await fetch(url);
      const text = await response.text();
      return {
        ok: response.ok,
        status: response.status,
        finalUrl: response.url,
        length: text.length,
        hasPrefill: text.includes("moss_tts_prefill.onnx"),
      };
    } catch (error) {
      return {
        ok: false,
        name: error?.name ?? "UnknownError",
        message: error?.message ?? String(error),
      };
    }
  }, MODEL_META_URL);
  console.log(`[voice-lab:native-fetch] ${JSON.stringify(nativeFetchProbe)}`);

  await page.getByRole("button", { name: "1단계 사전검증 시작" }).click();

  await page.waitForFunction(
    () => {
      const gate = document.documentElement.dataset.voiceLabGate1;
      const text = document.querySelector("#status")?.textContent ?? "";
      return gate === "pass" || gate === "fail" || text.includes("실패");
    },
    undefined,
    { timeout: 45_000 },
  );

  await snapshot("gate1-finished");

  const gate1 = await page.locator("html").getAttribute("data-voice-lab-gate1");
  const status = await page.locator("#status").innerText();
  if (gate1 !== "pass" || !status.includes("3/3 통과")) {
    throw new Error(`Gate 1 failed: ${status}`);
  }

  const sizeButton = page.getByRole("button", { name: "2A 모델 크기 검증 시작" });
  if (await sizeButton.isDisabled()) {
    throw new Error("Gate 2A button stayed disabled after Gate 1 PASS");
  }

  await sizeButton.click();
  await page.waitForFunction(
    () => ["pass", "fail"].includes(document.documentElement.dataset.voiceLabGate2a ?? ""),
    undefined,
    { timeout: 60_000 },
  );

  await snapshot("gate2a-finished");

  const gate2a = await page.locator("html").getAttribute("data-voice-lab-gate2a");
  const gate2Details = await page.locator("#gate2-details").innerText();
  const fp32 = await page.locator("#fp32-result").innerText();
  const int8 = await page.locator("#int8-result").innerText();
  const codec = await page.locator("#codec-result").innerText();

  if (gate2a !== "pass") {
    throw new Error(`Gate 2A failed: ${gate2Details}`);
  }
  if (![fp32, int8, codec].every((value) => value === "접근 가능")) {
    throw new Error(`Gate 2A badges unexpected: fp32=${fp32}, int8=${int8}, codec=${codec}`);
  }

  console.log(`[voice-lab] Gate 1 PASS: ${status}`);
  console.log(`[voice-lab] Gate 2A PASS: ${gate2Details}`);
} catch (error) {
  await snapshot("error").catch(() => {});
  console.error(`[voice-lab] captured failures=${JSON.stringify(failures, null, 2)}`);
  console.error(`[voice-lab] final url=${page.url()}`);
  throw error;
} finally {
  await browser.close();
}
