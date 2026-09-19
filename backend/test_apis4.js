const https = require('https');

function testUrl(label, opts, postData) {
  return new Promise((resolve) => {
    const req = https.request(opts, res => {
      let b = '';
      res.on('data', c => b += c);
      res.on('end', () => {
        console.log(`\n=== ${label} ===`);
        console.log(`Status: ${res.statusCode}`);
        console.log(`Body (first 1000): ${b.substring(0, 1000)}`);
        resolve();
      });
    });
    req.on('error', e => { console.log(`\n=== ${label} === ERROR: ${e.message}`); resolve(); });
    if (postData) req.write(postData);
    req.end();
  });
}

(async () => {
  // LG Next.js - RSC payload
  await testUrl('LG /_next/data', {
    hostname: 'globalcareers.lge.com',
    path: '/jobs?_rsc=1',
    method: 'GET',
    headers: { 'User-Agent': 'Mozilla/5.0', 'Accept': 'text/x-component', 'RSC': '1', 'Next-Router-State-Tree': '%5B%22%22%5D' }
  });

  // Darwinbox - look for internal API in the JS  
  await testUrl('Darwinbox /ms/candidatev2/getAllJobs', {
    hostname: 'airtel.darwinbox.in',
    path: '/ms/candidatev2/getAllJobs',
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'User-Agent': 'Mozilla/5.0', 'Accept': 'application/json' }
  }, JSON.stringify({page:1, limit:5}));
  
  await testUrl('Darwinbox /ms/candidatev2/getallJobs', {
    hostname: 'airtel.darwinbox.in',
    path: '/ms/candidatev2/getallJobs',
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'User-Agent': 'Mozilla/5.0', 'Accept': 'application/json' }
  }, JSON.stringify({page_no:1}));
})();
