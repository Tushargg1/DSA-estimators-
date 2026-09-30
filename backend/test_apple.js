const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
    const browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    await page.goto('https://jobs.apple.com/en-us/search?team=internships-STDNT-INTRN', { waitUntil: 'networkidle' });
    await page.waitForTimeout(5000);
    const html = await page.content();
    fs.writeFileSync('apple_html.txt', html);
    const jobs = await page.evaluate(() => {
        let list = [];
        document.querySelectorAll('a').forEach(a => {
            if (a.innerText && a.href && a.href.includes('details')) {
                list.push(a.innerText.trim());
            }
        });
        return list;
    });
    console.log("Jobs found:", jobs.length);
    await browser.close();
})();
