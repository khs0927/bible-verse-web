import { mkdir, writeFile } from "node:fs/promises";
import { webkit } from "playwright";

const browser = await webkit.launch();
const page = await browser.newPage({ viewport: { width: 390, height: 844 } });

page.on("console", (message) => console.log(`[browser:${message.type()}] ${message.text()}`));
page.on("pageerror", (error) => console.error(`[browser:pageerror] ${error.message}`));
page.on("requestfailed", (request) => {
  console.error(`[browser:requestfailed] ${request.failure()?.errorText ?? "failed"} ${request.url()}`);
});

async function waitGate(attribute, timeout) {
  await page.waitForFunction(
    (name) => ["pass", "fail"].includes(document.documentElement.getAttribute(name) ?? ""),
    attribute,
    { timeout },
  );
  return page.locator("html").getAttribute(attribute);
}

try {
  await page.goto("http://127.0.0.1:4173/", { waitUntil: "domcontentloaded", timeout: 30_000 });
  await page.waitForFunction(() => document.documentElement.dataset.voiceLabRuntime === "ready", undefined, { timeout: 15_000 });

  // Gate 1 also pins ORT WASM assets and enforces single-thread mode.
  await page.getByRole("button", { name: "1단계 사전검증 시작" }).click();
  const gate1 = await waitGate("data-voice-lab-gate1", 45_000);
  if (gate1 !== "pass") throw new Error(`Gate 1 failed: ${await page.locator("#status").innerText()}`);

  await page.getByRole("button", { name: "3A 한국어 토큰화 검증" }).click();
  const gate3a = await waitGate("data-voice-lab-gate3a", 60_000);
  if (gate3a !== "pass") throw new Error(`Gate 3A failed: ${await page.locator("#gate3a-details").innerText()}`);

  const synthesis = page.getByRole("button", { name: "3B 실제 한국어 음성 생성" });
  await synthesis.click();
  const gate3b = await waitGate("data-voice-lab-gate3b", 8 * 60_000);
  const details = await page.locator("#gate3b-details").innerText();
  console.log(`[voice-lab] Gate 3B=${gate3b} ${details}`);
  if (gate3b !== "pass") throw new Error(`Gate 3B failed: ${details}`);

  const audioInfo = await page.evaluate(async () => {
    const audio = document.querySelector("#synthesis-audio");
    if (!audio?.src) throw new Error("audio src is missing");
    const response = await fetch(audio.src);
    const bytes = new Uint8Array(await response.arrayBuffer());
    let binary = "";
    const chunk = 0x8000;
    for (let i = 0; i < bytes.length; i += chunk) {
      binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
    }
    return {
      base64: btoa(binary),
      byteLength: bytes.length,
      frames: document.documentElement.dataset.voiceLabGeneratedFrames ?? null,
    };
  });

  if (audioInfo.byteLength < 10_000) {
    throw new Error(`WAV output is unexpectedly small: ${audioInfo.byteLength}`);
  }

  await mkdir("voice-lab/build/quality", { recursive: true });
  await writeFile(
    "voice-lab/build/quality/moss-korean-xiaoyu.wav",
    Buffer.from(audioInfo.base64, "base64"),
  );
  await writeFile(
    "voice-lab/build/quality/result.txt",
    `Gate 3B PASS\n${details}\nbytes=${audioInfo.byteLength}\nframes=${audioInfo.frames}\n`,
  );
  console.log(`[voice-lab] WAV saved: ${audioInfo.byteLength} bytes, frames=${audioInfo.frames}`);
} finally {
  await browser.close();
}
