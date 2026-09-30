const { chromium } = require('playwright');
const JOB_URL_PATTERN = /\/job|\/career|\/apply|\/position|\/opening|\/vacanc|\/req|\/role|\/opportunity|\/posting|\/detail|\/join|\/intern|\/talent|\/hiring|\/work-with|\/open-position|\/current-open|join-us|work-with-us/i;

async function run() {
  const browser = await chromium.launch({ 
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-blink-features=AutomationControlled']
  });
  const context = await browser.newContext();
  
  for (let url of ['https://globalcareers.lge.com/jobs', 'https://kpmgindia.talentrecruit.com/career-page']) {
      const page = await context.newPage();
      await page.goto(url, { waitUntil: 'networkidle', timeout: 30000 });
      await page.waitForTimeout(5000); 
      const html = await page.content();
      
      // Match anything that looks like a URL path or full URL
      const pathRegex = /(?:https?:\/\/[a-zA-Z0-9.-]+)?(\/[a-zA-Z0-9_.-]+){2,}/gi;
      
      let matches = [];
      let m;
      while ((m = pathRegex.exec(html)) !== null) {
          const path = m[0];
          if (path.match(/.*\.(png|jpg|jpeg|gif|svg|ico|css|js|woff|woff2|ttf|eot)(\?.*)?$/i)) continue;
          if (JOB_URL_PATTERN.test(path)) {
              matches.push(path);
          }
      }
      console.log(`\n=== ${url} ===`);
      console.log("Raw matches:", [...new Set(matches)].slice(0, 10));
      await page.close();
  }
  await browser.close();
}
run();
