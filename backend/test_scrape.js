const { chromium } = require('playwright');

(async () => {
    const browser = await chromium.launch({ headless: true });
    const context = await browser.newContext();
    const page = await context.newPage();
    
    try {
        console.log('Navigating to site...');
        await page.goto('https://dsa-estimators.vercel.app', { waitUntil: 'networkidle' });
        
        console.log('Logging in...');
        await page.fill('input[type="email"]', 'tushar1233@gmail.com');
        await page.fill('input[type="password"]', 'Tushar@1233');
        await page.click('button[type="submit"]');
        
        console.log('Waiting for redirect...');
        await page.waitForTimeout(3000);
        
        console.log('Clicking Jobs in header or sidebar...');
        // Find the jobs link
        const jobsLink = page.locator('a[href="/jobs"]');
        if (await jobsLink.isVisible()) {
            await jobsLink.click();
            await page.waitForTimeout(2000);
        } else {
            console.log('Going directly to /jobs...');
            await page.goto('https://dsa-estimators.vercel.app/jobs');
            await page.waitForTimeout(2000);
        }
        
        console.log('Clicking Sources tab...');
        await page.click('button:has-text("Sources")').catch(() => console.log('Sources tab not found'));
        await page.waitForTimeout(2000);
        
        console.log('Taking screenshot of Sources tab...');
        await page.screenshot({ path: 'sources_tab.png' });
        
        console.log('Clicking Scrape Now on the first source...');
        const scrapeBtns = page.locator('button:has-text("Scrape now")');
        if (await scrapeBtns.count() > 0) {
            await scrapeBtns.first().click();
            console.log('Clicked! Waiting 15s for scrape...');
            await page.waitForTimeout(15000);
            
            console.log('Taking screenshot after scrape...');
            await page.screenshot({ path: 'after_scrape.png' });
        } else {
            console.log('No Scrape Now button found!');
        }
        
    } catch (e) {
        console.error('Error:', e);
    } finally {
        await browser.close();
    }
})();
