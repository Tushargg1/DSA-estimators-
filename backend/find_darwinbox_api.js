const https = require('https');

async function get(hostname, path, extraHeaders) {
  return new Promise((resolve) => {
    const options = {
      hostname,
      path,
      method: 'GET',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
        'Accept': 'application/json, text/html, */*',
        'Referer': `https://${hostname}/ms/candidatev2/main/careers/allJobs`,
        'X-Requested-With': 'XMLHttpRequest',
        ...extraHeaders
      }
    };
    const req = https.request(options, (res) => {
      let data = '';
      res.on('data', d => data += d);
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: data }));
    });
    req.on('error', e => resolve({ status: 'ERROR', body: e.message }));
    req.end();
  });
}

async function main() {
  const host = 'airtel.darwinbox.in';
  
  const paths = [
    '/ms/candidatev2/main/careers/getJobOpenings',
    '/ms/candidatev2/main/careers/getAllJobs',
    '/ms/candidatev2/api/careers/jobs',
  ];
  
  for (const p of paths) {
    console.log('\n=== GET', p, '===');
    const r = await get(host, p);
    console.log('Status:', r.status);
    console.log('Content-Type:', r.headers['content-type']);
    console.log('Body (first 2000 chars):', r.body.substring(0, 2000));
  }
  
  // Also try with different Accept headers
  console.log('\n=== GET /ms/candidatev2/main/careers/getJobOpenings (JSON Accept) ===');
  const r2 = await get(host, '/ms/candidatev2/main/careers/getJobOpenings', { 'Accept': 'application/json' });
  console.log('Status:', r2.status);
  console.log('Content-Type:', r2.headers['content-type']);
  console.log('Body:', r2.body.substring(0, 2000));
}

main().catch(console.error);
