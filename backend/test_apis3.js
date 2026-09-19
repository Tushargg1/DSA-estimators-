const https = require('https');
const http = require('http');

function testUrl(label, opts, postData) {
  return new Promise((resolve) => {
    const mod = opts.port === 80 ? http : https;
    const req = mod.request(opts, res => {
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
  // LG - check if there's an __NEXT_DATA__ script tag with jobs
  await testUrl('LG HTML source', {
    hostname: 'globalcareers.lge.com',
    path: '/jobs',
    method: 'GET',
    headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)', 'Accept': 'text/html' }
  });

  // Darwinbox - check HTML source for API clues
  await testUrl('Darwinbox HTML', {
    hostname: 'airtel.darwinbox.in',
    path: '/ms/candidatev2/main/careers/allJobs',
    method: 'GET',
    headers: { 'User-Agent': 'Mozilla/5.0', 'Accept': 'text/html' }
  });
})();
