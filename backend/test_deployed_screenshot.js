const { chromium } = require('playwright');
const fs = require('fs');

(async () => {
    console.log('Launching browser...');
    const browser = await chromium.launch({ headless: true });
    const context = await browser.newContext();
    const page = await context.newPage();
    
    try {
        console.log('Navigating to https://dsa-estimators.vercel.app ...');
        await page.goto('https://dsa-estimators.vercel.app', { waitUntil: 'networkidle' });
        
        await page.screenshot({ path: 'login_page.png' });
        console.log('Took screenshot of login page: login_page.png');
        
        console.log('Page title:', await page.title());
        console.log('Body text length:', (await page.innerText('body')).length);
        
    } catch (e) {
        console.error('Error during automation:', e);
    } finally {
        await browser.close();
    }
})();
