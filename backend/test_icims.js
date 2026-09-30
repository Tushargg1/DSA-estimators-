const https = require('https');

function get(url, extraHeaders = {}) {
  return new Promise((resolve) => {
    const parsed = new URL(url);
    const r = https.request({
      hostname: parsed.hostname, path: parsed.pathname + parsed.search, method: 'GET',
      headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)', 'Accept': 'application/json, text/html, */*', ...extraHeaders }
    }, (res) => {
      let b = ''; res.on('data', c => b += c);
      res.on('end', () => resolve({ status: res.statusCode, ct: res.headers['content-type'], location: res.headers['location'], body: b }));
    });
    r.on('error', e => resolve({ status: 'ERR', body: e.message }));
    r.end();
  });
}

async function main() {
  // iCIMS has a JSON API for job search
  // Format: https://{company}.icims.com/jobs/search?pr=1&format=json&in=1
  // But companies use numeric IDs, like recruitmilitary.icims.com or specific subdomains
  
  console.log('=== iCIMS - try common subdomains ===');
  const companies = [
    { name: 'Amazon', host: 'amazon.icims.com' },
    { name: 'JPMorgan', host: 'jpmc.icims.com' },
    { name: 'Google', host: 'google.icims.com' },
    { name: 'careers-icims test', host: 'careers.icims.com' },
  ];
  for (const c of companies) {
    const r = await get('https://' + c.host + '/jobs/search?pr=1&format=json&in=1').catch(e => ({ status: 'ERR', body: e.message }));
    console.log('[' + r.status + '] iCIMS ' + c.name + ':', (r.body || '').substring(0, 150));
  }
  
  console.log('\n=== iCIMS documented API endpoint ===');
  // iCIMS has a documented public API: GET /jobs/{jobId}?format=json
  // Companies hosting on iCIMS have subdomains like: xyz.icims.com
  // The search endpoint is: GET /jobs/search?pr=1&format=json&in=1
  const r2 = await get('https://careers.icims.com/jobs/search?pr=1&format=json&in=1');
  console.log('iCIMS careers search status:', r2.status, 'location:', r2.location);
  if (r2.location) {
    const r3 = await get('https://careers.icims.com' + r2.location);
    console.log('After redirect [' + r3.status + ']:', r3.body.substring(0, 300));
  }

  console.log('\n=== JobVite API ===');
  // Jobvite: GET https://jobs.jobvite.com/api/company/{company}/job
  const jobviteCos = ['Microsoft', 'Salesforce', 'Adobe', 'Netflix'];
  for (const c of jobviteCos) {
    const r = await get('https://jobs.jobvite.com/api/company/' + c + '/job').catch(e => ({ status: 'ERR', body: e.message }));
    console.log('[' + r.status + '] Jobvite ' + c + ':', (r.body || '').substring(0, 150));
  }
}

main().catch(console.error);
