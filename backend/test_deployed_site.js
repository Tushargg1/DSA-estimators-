const { chromium } = require('playwright');

(async () => {
    console.log('Launching browser...');
    const browser = await chromium.launch({ headless: true });
    const context = await browser.newContext();
    const page = await context.newPage();
    
    try {
        console.log('Navigating to https://dsa-estimators.vercel.app ...');
        await page.goto('https://dsa-estimators.vercel.app', { waitUntil: 'networkidle' });
        
        console.log('Attempting to log in...');
        // Look for the login form. Adjust selectors as needed based on the actual UI.
        await page.fill('input[type="email"]', 'tushar1233@gmail.com').catch(() => console.log('Email field not found immediately'));
        
        if (await page.isVisible('input[type="email"]')) {
            await page.fill('input[type="password"]', 'Tushar@1233');
            await page.click('button[type="submit"]');
            await page.waitForTimeout(3000); // wait for login redirect
            console.log('Login form submitted.');
        } else {
            console.log('No login form found. Maybe already logged in or different UI structure?');
        }
        
        console.log('Switching to Sources tab...');
        // Find and click the Sources tab.
        await page.click('button:has-text("Sources")').catch(e => console.log('Could not find Sources tab:', e.message));
        await page.waitForTimeout(2000);
        
        console.log('Fetching all sources displayed...');
        const sources = await page.locator('.company-group-card').all();
        console.log(`Found ${sources.length} sources on the page.`);
        
        let scrapeTested = false;
        
        for (let i = 0; i < sources.length; i++) {
            const card = sources[i];
            const headerText = await card.locator('h4').innerText().catch(() => 'Unknown Source');
            console.log(`\nSource ${i+1}: ${headerText}`);
            
            // Expand the source if it is not expanded
            await card.locator('.company-group-header').click().catch(() => {});
            await page.waitForTimeout(1000);
            
            // Read the stats text (total jobs seen, stored listings, etc.)
            const metaText = await card.locator('.company-group-meta').innerText().catch(() => 'No stats available');
            console.log(`Stats before scrape: \n${metaText.replace(/\n/g, ', ')}`);
            
            // Only test scrape on one source to verify it works (to avoid spamming the backend/portals)
            if (!scrapeTested && headerText.toLowerCase().includes('careersat.tech')) {
                console.log(`=> Clicking "Scrape now" on ${headerText}...`);
                const scrapeBtn = card.locator('button:has-text("Scrape now")');
                if (await scrapeBtn.isVisible()) {
                    await scrapeBtn.click();
                    scrapeTested = true;
                    
                    console.log('Waiting for scraping to complete (waiting 15s max)...');
                    // Wait until the button text reverts back to 'Scrape now' (meaning loading finished)
                    // Or wait for success message
                    await page.waitForTimeout(10000); // 10s wait for network/db ops
                    
                    const metaTextAfter = await card.locator('.company-group-meta').innerText().catch(() => 'No stats available');
                    console.log(`Stats AFTER scrape: \n${metaTextAfter.replace(/\n/g, ', ')}`);
                    
                    const successMessage = await page.locator('.job-success').innerText().catch(() => 'No success message found');
                    console.log(`Notification: ${successMessage}`);
                } else {
                    console.log('Scrape button not visible.');
                }
            }
        }
        
        if (!scrapeTested && sources.length > 0) {
            console.log('\n=> Clicking "Scrape now" on the first available source since CareersAtTech was not found...');
            const card = sources[0];
            const scrapeBtn = card.locator('button:has-text("Scrape now")');
            if (await scrapeBtn.isVisible()) {
                await scrapeBtn.click();
                await page.waitForTimeout(15000); // wait
                const metaTextAfter = await card.locator('.company-group-meta').innerText().catch(() => 'No stats available');
                console.log(`Stats AFTER scrape for first source: \n${metaTextAfter.replace(/\n/g, ', ')}`);
            }
        }
        
    } catch (e) {
        console.error('Error during automation:', e);
    } finally {
        await browser.close();
    }
})();
