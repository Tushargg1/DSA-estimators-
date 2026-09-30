const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
    const browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    await page.goto('https://fa-esra-saasfaprod1.fa.ocs.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX_1/requisitions?location=India&locationId=300000000216666&locationLevel=country', { waitUntil: 'networkidle' });
    await page.waitForTimeout(5000);
    const html = await page.content();
    fs.writeFileSync('oracle_html.txt', html);
    await browser.close();
})();
