import { webkit } from "playwright";

const browser = await webkit.launch();
const page = await browser.newPage({ viewport: { width: 390, height: 844 } });

page.on("console", (message) => {
  console.log(`[browser:${message.type()}] ${message.text()}`);
});
page.on("pageerror", (error) => {
  console.error(`[browser:error] ${error.message}`);
});

try {
  await page.goto("http://127.0.0.1:4173/", { waitUntil: "networkidle", timeout: 30_000 });
  await page.getByRole("button", { name: "1단계 사전검증 시작" }).click();

  await page.waitForFunction(
    () => document.querySelector("#status")?.textContent?.includes("3/3 통과"),
    undefined,
    { timeout: 30_000 },
  );

  const status = await page.locator("#status").innerText();
  const details = await page.locator("#details").innerText();
  console.log(`[voice-lab] ${status}`);
  console.log(`[voice-lab] ${details}`);
} finally {
  await browser.close();
}
