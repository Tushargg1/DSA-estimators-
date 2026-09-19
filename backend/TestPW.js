
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto('https://careersat.tech/jobs', { waitUntil: 'networkidle' });
  const html = await page.content();
  const links = await page.evaluate(() => Array.from(document.querySelectorAll('a')).map(a => a.href));
  console.log('Total a tags:', links.length);
  console.log(links.filter(l => l.includes('job') || l.includes('career')).slice(0, 5));
  await browser.close();
})();

