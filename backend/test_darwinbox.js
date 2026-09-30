// Test Darwinbox - can we call their internal API once we know the structure?
// From our Playwright test, we know Darwinbox job URLs look like:
// /ms/candidatev2/main/careers/jobDetails/{hash}
// Let's try to find the API that serves these job lists

const https = require('https');

function req(opts, body = null) {
  return new Promise((resolve, reject) => {
    const r = https.request(opts, res => {
      let b = '';
      res.on('data', c => b += c);
      res.on('end', () => resolve({ status: res.statusCode, body: b }));
    });
    r.on('error', reject);
    if (body) r.write(body);
    r.end();
  });
}

(async () => {
  // Check Darwinbox's Angular app for API calls it makes
  // The SPA makes XHR calls to get job data - let's find the endpoint
  // Common Darwinbox patterns:
  const endpoints = [
    { method: 'GET', path: '/ms/candidatev2/main/careers/getJobListData', body: null },
    { method: 'POST', path: '/ms/candidatev2/main/careers/getJobList', body: '{"page":1}' },
    { method: 'GET', path: '/ms/candidatev2/candidateportal/jobPostings', body: null },
    { method: 'GET', path: '/ms/candidatev2/main/careers/getActiveJobs', body: null },
    // Darwinbox has a standard pattern for their portal
    { method: 'POST', path: '/ms/candidatev2/main/careers/search', body: JSON.stringify({keyword:'',location:'',page:1,per_page:20}) },
    { method: 'GET', path: '/ms/candidatev2/main/careers/getAllJobs?page=1&per_page=20', body: null },
    { method: 'GET', path: '/ms/candidatev2/main/careers/jobs?page=1', body: null },
  ];
  
  for (const ep of endpoints) {
    const headers = {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
      'Accept': 'application/json, text/plain, */*',
      'Referer': 'https://airtel.darwinbox.in/ms/candidatev2/main/careers/allJobs',
      'X-Requested-With': 'XMLHttpRequest',
    };
    if (ep.body) {
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = Buffer.byteLength(ep.body).toString();
    }
    const res = await req({
      hostname: 'airtel.darwinbox.in',
      path: ep.path,
      method: ep.method,
      headers
    }, ep.body).catch(e => ({ status: 'ERR', body: e.message }));
    console.log(`[${res.status}] ${ep.method} ${ep.path}: ${res.body.substring(0, 150)}`);
  }
})();
