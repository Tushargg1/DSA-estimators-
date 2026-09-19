const https = require('https');

function testUrl(label, opts, postData) {
  return new Promise((resolve) => {
    const req = https.request(opts, res => {
      let b = '';
      res.on('data', c => b += c);
      res.on('end', () => {
        console.log(`\n=== ${label} ===`);
        console.log(`Status: ${res.statusCode}`);
        console.log(`Body (first 400): ${b.substring(0, 400)}`);
        resolve();
      });
    });
    req.on('error', e => { console.log(`\n=== ${label} === ERROR: ${e.message}`); resolve(); });
    if (postData) req.write(postData);
    req.end();
  });
}

(async () => {
  // LG - try different API patterns
  await testUrl('LG /api/recruit/job/list', {
    hostname: 'globalcareers.lge.com',
    path: '/api/recruit/job/list',
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'User-Agent': 'Mozilla/5.0' }
  }, JSON.stringify({ page: 1, size: 5 }));

  // Darwinbox (Airtel)
  await testUrl('Airtel Darwinbox', {
    hostname: 'airtel.darwinbox.in',
    path: '/ms/candidatev2/main/careers/allJobs',
    method: 'GET',
    headers: { 'User-Agent': 'Mozilla/5.0', 'Accept': 'application/json' }
  });

  // KPMG TalentRecruit 
  await testUrl('KPMG TalentRecruit', {
    hostname: 'kpmgindia.talentrecruit.com',
    path: '/career-page',
    method: 'GET',
    headers: { 'User-Agent': 'Mozilla/5.0', 'Accept': 'text/html' }
  });
})();
