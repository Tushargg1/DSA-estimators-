const { chromium } = require('playwright');
const JOB_URL_PATTERN = /\/job|\/career|\/apply|\/position|\/opening|\/vacanc|\/req|\/role|\/opportunity|\/posting|\/detail|\/join|\/intern|\/talent|\/hiring|\/work-with|\/open-position|\/current-open|join-us|work-with-us/i;

async function testScrape(url) {
  console.log(`\n=== Testing ${url} ===`);
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
  const context = await browser.newContext({
    userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
  });
  const page = await context.newPage();
  try {
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
    try {
      await page.waitForLoadState('networkidle', { timeout: 15000 });
    } catch(e) {}
    
    // mimic wait for selector
    const jobListSelectors = [
      "a[href*='/job']", "a[href*='/career']", "a[href*='/opening']",
      "[class*='job-card']", "[class*='jobCard']", "[class*='job-listing']",
      "[class*='position']", "[class*='vacancy']", "[data-job-id]",
      "li[class*='job']", "div[class*='job-item']"
    ];
    for (let s of jobListSelectors) {
      try { await page.waitForSelector(s, { timeout: 3000 }); break; } catch(e) {}
    }

    const html = await page.content();
    let matches = [];
    const hrefRegex = /href\s*=\s*["']([^"']{10,2048})["']/gi;
    let m;
    while ((m = hrefRegex.exec(html)) !== null) {
      const href = m[1].trim();
      const lower = href.toLowerCase();
      if (lower.match(/.*\.(png|jpg|jpeg|gif|svg|ico|css|js|woff|woff2|ttf|eot)(\?.*)?$/)) continue;
      if (JOB_URL_PATTERN.test(href)) {
        matches.push(href);
      }
    }
    console.log(`Found ${matches.length} matching job links.`);
    if (matches.length > 0) {
      console.log('Sample:', [...new Set(matches)].slice(0, 5));
    } else {
        console.log('No matches found!');
    }
  } catch(e) {
    console.log('Error:', e.message);
  } finally {
    await browser.close();
  }
}

(async () => {
  await testScrape('https://airtel.darwinbox.in/ms/candidatev2/main/careers/allJobs');
  await testScrape('https://globalcareers.lge.com/jobs');
  await testScrape('https://kpmgindia.talentrecruit.com/career-page');
  await testScrape('https://careersat.tech/jobs');
})();
