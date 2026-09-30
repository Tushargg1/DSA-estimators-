const { chromium } = require('playwright');
async function run() {
  const browser = await chromium.launch({ 
    headless: true,
    args: [
        '--no-sandbox',
        '--disable-setuid-sandbox',
        '--disable-dev-shm-usage',
        '--disable-gpu',
        '--disable-blink-features=AutomationControlled'
    ]
  });
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto('https://kpmgindia.talentrecruit.com/career-page', { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(5000); 
  const links = await page.evaluate(() => Array.from(document.querySelectorAll('a')).map(a => a.href));
  console.log("All links on KPMG:", links);
  await browser.close();
}
run();
