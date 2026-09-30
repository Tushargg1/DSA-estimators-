const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({ headless: true }); 
  const context = await browser.newContext();
  const page = await context.newPage();

  await page.goto('https://careersat.tech/jobs', { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle');

  const nextData = await page.evaluate(() => {
    const el = document.getElementById('__NEXT_DATA__');
    return el ? el.textContent : null;
  });
  
  if (nextData) {
      const data = JSON.parse(nextData);
      if (data.props && data.props.pageProps && data.props.pageProps.initialJobs && data.props.pageProps.initialJobs.data) {
          const firstJob = data.props.pageProps.initialJobs.data[0];
          console.log('First job structure:', JSON.stringify(firstJob, null, 2));
      }
  }

  await browser.close();
})();
