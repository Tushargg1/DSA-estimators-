const { chromium } = require('playwright');

async function testSite(url, interceptors = []) {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext();
  const page = await context.newPage();
  
  const responses = [];
  page.on('response', async res => {
    const reqUrl = res.url();
    for (let interceptor of interceptors) {
      if (reqUrl.includes(interceptor)) {
        try {
          const json = await res.json();
          responses.push({ url: reqUrl, data: json });
        } catch(e) {}
      }
    }
  });

  await page.goto(url, { waitUntil: 'networkidle', timeout: 30000 }).catch(e => console.log(e.message));
  await page.waitForTimeout(5000);
  console.log(`\n=== URL: ${url} ===`);
  for (let r of responses) {
    console.log(`API Intercepted: ${r.url}`);
    if (r.data) console.log(JSON.stringify(r.data).substring(0, 300));
  }
  await browser.close();
}

(async () => {
  await testSite('https://globalcareers.lge.com/jobs', ['/api/recruit/jobList', '/graphql', '/api']);
  await testSite('https://kpmgindia.talentrecruit.com/career-page', ['/api']);
  await testSite('https://careersat.tech/jobs', ['/api', '_next/data']);
  await testSite('https://airtel.darwinbox.in/ms/candidatev2/main/careers/allJobs', ['darwinbox']);
})();
