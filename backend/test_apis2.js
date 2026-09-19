const https = require('https');

function testUrl(label, opts, postData) {
  return new Promise((resolve) => {
    const req = https.request(opts, res => {
      let b = '';
      res.on('data', c => b += c);
      res.on('end', () => {
        console.log(`\n=== ${label} ===`);
        console.log(`Status: ${res.statusCode}`);
        console.log(`Body (first 600): ${b.substring(0, 600)}`);
        resolve();
      });
    });
    req.on('error', e => { console.log(`\n=== ${label} === ERROR: ${e.message}`); resolve(); });
    if (postData) req.write(postData);
    req.end();
  });
}

(async () => {
  // Darwinbox API - the actual XHR endpoint
  await testUrl('Airtel Darwinbox API', {
    hostname: 'airtel.darwinbox.in',
    path: '/ms/candidatev2/candidateapiv2/allJobs',
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'User-Agent': 'Mozilla/5.0', 'Accept': 'application/json' }
  }, JSON.stringify({ page: 1, limit: 5 }));

  // Try LG with different body
  await testUrl('LG /api/recruit/job/list v2', {
    hostname: 'globalcareers.lge.com',
    path: '/api/recruit/job/list',
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'User-Agent': 'Mozilla/5.0', 'Accept': 'application/json' }
  }, JSON.stringify({ pageNo: 1, pageSize: 5, country: '', keyword: '' }));

  // Try LG alternative
  await testUrl('LG /api/v1/jobs', {
    hostname: 'globalcareers.lge.com',
    path: '/api/v1/jobs',
    method: 'GET',
    headers: { 'User-Agent': 'Mozilla/5.0', 'Accept': 'application/json' }
  });

  // Try KPMG TalentRecruit API
  await testUrl('KPMG TalentRecruit API', {
    hostname: 'kpmgindia.talentrecruit.com',
    path: '/portal/newjoblist.php',
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'User-Agent': 'Mozilla/5.0', 'Accept': 'application/json' }
  }, 'page=1&limit=5');
})();
