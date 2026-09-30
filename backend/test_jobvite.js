const https = require('https');
const http = require('http');

function request(url, extraHeaders = {}) {
  return new Promise((resolve) => {
    const parsed = new URL(url);
    const lib = parsed.protocol === 'https:' ? https : http;
    const r = lib.request({
      hostname: parsed.hostname, path: parsed.pathname + parsed.search, method: 'GET',
      headers: { 'User-Agent': 'Mozilla/5.0', 'Accept': 'application/json, text/html, */*', ...extraHeaders }
    }, (res) => {
      let b = ''; res.on('data', c => b += c);
      res.on('end', () => resolve({ status: res.statusCode, ct: res.headers['content-type'], location: res.headers['location'], body: b }));
    });
    r.on('error', e => resolve({ status: 'ERR', body: e.message }));
    r.end();
  });
}

async function followRedirect(url, limit = 3) {
  let r = await request(url);
  while ((r.status === 301 || r.status === 302 || r.status === 303) && r.location && limit > 0) {
    const next = r.location.startsWith('http') ? r.location : new URL(r.location, url).href;
    r = await request(next);
    limit--;
  }
  return r;
}

async function main() {
  console.log('=== Jobvite API ===');
  // Jobvite: GET https://jobs.jobvite.com/api/company/{company}/job
  const jobviteCos = [
    { name: 'Microsoft', slug: 'microsoft' },
    { name: 'Salesforce', slug: 'salesforce' },
    { name: 'Cisco', slug: 'cisco' },
    { name: 'Intuit', slug: 'intuit' },
    { name: 'AppDynamics', slug: 'appdynamics' },
  ];
  for (const c of jobviteCos) {
    const r = await followRedirect('https://jobs.jobvite.com/api/company/' + c.slug + '/job');
    const preview = (r.body || '').substring(0, 200);
    console.log('[' + r.status + '] Jobvite ' + c.name + ': CT=' + (r.ct||'').substring(0,40) + ' | ' + preview);
  }

  console.log('\n=== iCIMS JSON API ===');
  // Amazon iCIMS works: need the correct subdomain
  const r = await request('https://amazon.icims.com/jobs/search?pr=1&format=json&in=1');
  console.log('[' + r.status + '] Amazon iCIMS: location=' + r.location);
  if (r.location) {
    const r2 = await followRedirect('https://amazon.icims.com' + r.location);
    console.log('[' + r2.status + '] After redirect CT:', (r2.ct||''), 'Body:', r2.body.substring(0, 300));
  }
}

main().catch(console.error);
