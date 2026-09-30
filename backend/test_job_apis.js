// Test Adzuna API (fully free, no key needed for basic search)
const https = require('https');

function get(url) {
  return new Promise((resolve, reject) => {
    https.get(url, { headers: { 'Accept': 'application/json' } }, res => {
      let b = '';
      res.on('data', c => b += c);
      res.on('end', () => resolve({ status: res.statusCode, body: b.substring(0, 500) }));
    }).on('error', reject);
  });
}

(async () => {
  // Adzuna API - free tier with app_id + app_key (free signup)
  // https://developer.adzuna.com/
  const r1 = await get('https://api.adzuna.com/v1/api/jobs/in/search/1?app_id=test&app_key=test&company=LG%20Electronics&results_per_page=5');
  console.log('Adzuna LG:', r1.status, r1.body);

  // RemoteOK API - free, no auth needed, only remote jobs
  const r2 = await get('https://remoteok.com/api?tag=developer');
  console.log('RemoteOK:', r2.status, r2.body.substring(0, 200));
  
  // The Muse API - free, no auth needed
  const r3 = await get('https://www.themuse.com/api/public/jobs?company=LG%20Electronics&page=0&descending=true');
  console.log('The Muse LG:', r3.status, r3.body.substring(0, 300));
  
  // Arbeitnow (free, no auth, mostly EU)
  const r4 = await get('https://www.arbeitnow.com/api/job-board-api?company=lg');
  console.log('Arbeitnow LG:', r4.status, r4.body.substring(0, 200));
})();
