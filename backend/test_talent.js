const { chromium } = require('playwright');
const fs = require('fs');

(async () => {
  const browser = await chromium.launch({ headless: false }); 
  const context = await browser.newContext();
  const page = await context.newPage();

  console.log('Navigating to TalentRecruit...');
  await page.goto('https://kpmgindia.talentrecruit.com/career-page', { waitUntil: 'networkidle', timeout: 60000 });
  await page.waitForTimeout(10000); 

  for (const frame of page.frames()) {
      if (frame.url().includes('appcareer')) {
          const paginationHtml = await frame.evaluate(() => {
              // find the paginator
              const paginator = document.querySelector('.mat-paginator, mat-paginator');
              return paginator ? paginator.innerHTML : 'Not found';
          });
          fs.writeFileSync('talent_paginator.txt', paginationHtml);
          console.log('Saved talent_paginator.txt');
      }
  }
  await browser.close();
})();
