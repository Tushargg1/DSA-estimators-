// Test actual sites by fetching their HTML and counting job links 
const https = require('https');
const http = require('http');

// Enhanced pattern matching
const HREF_PATTERN = /href\s*=\s*["']([^"']{10,2048})["']/gi;
const JOB_URL_PATTERN_OLD = /\/job|\/career|\/apply|\/position|\/opening|\/vacanc|\/req|\/role|\/opportunity|\/posting|\/detail/i;
const JOB_URL_PATTERN_NEW = /\/job|\/career|\/apply|\/position|\/opening|\/vacanc|\/req|\/role|\/opportunity|\/posting|\/detail|\/join|\/work-with|\/current-open|\/open-position|\/hiring|\/talent|\/intern|join-us|work-with-us/i;

function fetchUrl(url) {
  return new Promise((resolve, reject) => {
    const mod = url.startsWith('https') ? https : http;
    const opts = { headers: { 
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0',
      'Accept': 'text/html',
      'Accept-Language': 'en-US,en;q=0.9'
    }};
    mod.get(url, opts, res => {
      // Handle redirects
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        const redirUrl = res.headers.location.startsWith('http') ? res.headers.location : new URL(res.headers.location, url).href;
        return fetchUrl(redirUrl).then(resolve).catch(reject);
      }
      let body = '';
      res.on('data', c => body += c);
      res.on('end', () => resolve({ status: res.statusCode, body }));
    }).on('error', reject);
  });
}

function testSite(name, url) {
  return fetchUrl(url).then(({ status, body }) => {
    console.log(`\n=== ${name} (${status}) ===`);
    console.log(`Body length: ${body.length} chars`);
    
    let allLinks = 0, oldMatches = 0, newMatches = [];
    let m;
    HREF_PATTERN.lastIndex = 0;
    while ((m = HREF_PATTERN.exec(body)) !== null) {
      allLinks++;
      const href = m[1];
      if (JOB_URL_PATTERN_OLD.test(href)) oldMatches++;
      if (JOB_URL_PATTERN_NEW.test(href) && !JOB_URL_PATTERN_OLD.test(href)) {
        newMatches.push(href);
      }
    }
    console.log(`Total hrefs: ${allLinks}`);
    console.log(`Old pattern matches: ${oldMatches}`);
    console.log(`New-only matches: ${newMatches.length}`);
    if (newMatches.length > 0) console.log(`New matches:`, newMatches.slice(0, 5));
  }).catch(err => {
    console.log(`\n=== ${name} === ERROR: ${err.message}`);
  });
}

(async () => {
  await testSite('Airtel Darwinbox', 'https://airtel.darwinbox.in/ms/candidatev2/main/careers/allJobs');
  await testSite('LG Global Careers', 'https://globalcareers.lge.com/jobs');
  await testSite('KPMG India', 'https://kpmgindia.talentrecruit.com/career-page');
  await testSite('careersat.tech', 'https://careersat.tech/jobs');
})();
