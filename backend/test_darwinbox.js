const { chromium } = require('playwright');
const fs = require('fs');

(async () => {
  const browser = await chromium.launch({ headless: false }); 
  const context = await browser.newContext();
  const page = await context.newPage();

  console.log('Navigating to Darwinbox...');
  await page.goto('https://airtel.darwinbox.in/ms/candidatev2/main/careers/allJobs', { waitUntil: 'networkidle', timeout: 60000 });
  await page.waitForTimeout(15000); 

  const htmls = await page.evaluate(() => {
      let jobs = [];
      // We know darwinbox uses web components like `tb-card`, `tb-card-content`, `my-component`
      // Let's dump text content of the body just to see
      let cards = document.querySelectorAll('tb-card, [class*=card], .card');
      for (let card of cards) {
          if (card.innerText.includes('Manager') || card.innerText.includes('Engineer')) {
             jobs.push({
                 className: card.className,
                 tagName: card.tagName,
                 html: card.innerHTML.substring(0, 500)
             });
          }
      }
      return jobs;
  });
  
  fs.writeFileSync('darwinbox_jobs.json', JSON.stringify(htmls, null, 2));
  console.log('Saved darwinbox_jobs.json');

  await browser.close();
})();
