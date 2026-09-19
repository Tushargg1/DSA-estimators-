const https = require('https');

function testUrl(label, opts, postData) {
  return new Promise((resolve) => {
    const req = https.request(opts, res => {
      let b = '';
      res.on('data', c => b += c);
      res.on('end', () => {
        console.log(`\n=== ${label} ===`);
        console.log(`Status: ${res.statusCode}`);
        console.log(`Body (first 500): ${b.substring(0, 500)}`);
        resolve();
      });
    });
    req.on('error', e => { console.log(`\n=== ${label} === ERROR: ${e.message}`); resolve(); });
    if (postData) req.write(postData);
    req.end();
  });
}

(async () => {
  // Deloitte - check if it's a standard job board with an API
  await testUrl('Deloitte southasiacareers', {
    hostname: 'southasiacareers.deloitte.com',
    path: '/go/Deloitte-India/718244/',
    method: 'GET',
    headers: { 'User-Agent': 'Mozilla/5.0', 'Accept': 'application/json' }
  });

  // LG - try their specific API that the Next.js app calls
  await testUrl('LG getServerSideProps', {
    hostname: 'globalcareers.lge.com',
    path: '/_next/data/6IGNZXx7VYpqrJfLtE2Vx/jobs.json',
    method: 'GET',
    headers: { 'User-Agent': 'Mozilla/5.0', 'Accept': 'application/json' }
  });

  // KPMG TalentRecruit - try with more headers 
  await testUrl('KPMG with Referer', {
    hostname: 'kpmgindia.talentrecruit.com',
    path: '/career-page',
    method: 'GET',
    headers: {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
      'Accept': 'text/html',
      'Referer': 'https://www.google.com/'
    }
  });
})();
