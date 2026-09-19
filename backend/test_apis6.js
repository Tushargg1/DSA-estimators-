const https = require('https');

function testUrl(label, opts, postData) {
  return new Promise((resolve) => {
    const req = https.request(opts, res => {
      let b = '';
      res.on('data', c => b += c);
      res.on('end', () => {
        console.log(`\n=== ${label} ===`);
        console.log(`Status: ${res.statusCode}`);
        console.log(`Body (first 800): ${b.substring(0, 800)}`);
        resolve();
      });
    });
    req.on('error', e => { console.log(`\n=== ${label} === ERROR: ${e.message}`); resolve(); });
    if (postData) req.write(postData);
    req.end();
  });
}

(async () => {
  // KPMG TalentRecruit - look for XHR API in the HTML
  const getHtml = () => new Promise(resolve => {
    https.get('https://kpmgindia.talentrecruit.com/career-page', {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
        'Referer': 'https://www.google.com/'
      }
    }, res => {
      let b = '';
      res.on('data', c => b += c);
      res.on('end', () => resolve(b));
    });
  });
  
  const html = await getHtml();
  // Look for API endpoints, JSON, or script tags with data
  const apiMatches = html.match(/\/api\/[^"']+|getJobList|searchJobs|joblist|getAllJobs/gi);
  console.log('KPMG API patterns found:', apiMatches);
  
  // Check if there are job listings in the HTML directly
  const jobLinks = html.match(/href="[^"]*job[^"]*"/gi);
  console.log('KPMG job links in HTML:', jobLinks?.length || 0, jobLinks?.slice(0, 5));

  // TalentRecruit typical API
  await testUrl('KPMG /api/getjoblist', {
    hostname: 'kpmgindia.talentrecruit.com',
    path: '/api/getjoblist',
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
      'Referer': 'https://kpmgindia.talentrecruit.com/career-page',
      'Accept': 'application/json'
    }
  }, JSON.stringify({page:1, limit:5}));

  await testUrl('KPMG /portal/getjoblist', {
    hostname: 'kpmgindia.talentrecruit.com',
    path: '/portal/getjoblist',
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
      'Referer': 'https://kpmgindia.talentrecruit.com/career-page',
      'Accept': 'application/json'
    }
  }, JSON.stringify({page:1, limit:5}));
})();
