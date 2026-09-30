const { chromium } = require('playwright');
async function run() {
  const browser = await chromium.launch({ 
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-blink-features=AutomationControlled']
  });
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto('https://globalcareers.lge.com/jobs', { waitUntil: 'domcontentloaded', timeout: 30000 });
  const html = await page.content();
  const nextData = html.match(/<script id="__NEXT_DATA__"[^>]*>(.*?)<\/script>/s);
  if (nextData) {
     console.log("__NEXT_DATA__ found! Length:", nextData[1].length);
     try {
         const data = JSON.parse(nextData[1]);
         console.log("Keys:", Object.keys(data));
         // Print some part of it to see if it has jobs
         const props = data.props;
         console.log("Props keys:", Object.keys(props));
     } catch(e) { console.log(e); }
  } else {
     console.log("No __NEXT_DATA__ found.");
  }
  await browser.close();
}
run();
