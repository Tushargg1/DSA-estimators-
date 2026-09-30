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
  console.log('=== Oracle Taleo (Cognizant) ===');
  // Taleo REST API: GET https://{company}.taleo.net/careersection/{sectionid}/jobsearch.ftl?lang=en  
  // JSON API: GET /{company}.taleo.net/careersection/jobsearch.ftl?lang=en&format=json
  const cogPaths = [
    '/careersection/10000/jobsearch.ftl?lang=en&format=json',
    '/careersection/10003/jobsearch.ftl?lang=en&format=json',
    '/careersection/jobsearch.ftl?lang=en&format=json',
    '/careersection/1/jobsearch.ftl?lang=en&format=json',
    // Taleo has a REST API
    '/smartorg/i18n/taleoClientProxy.jsf?lang=en&career=1&noback=true',
    '/careersection/10000/jobsearch.ftl?lang=en',
  ];
  for (const p of cogPaths) {
    const r = await get('https://cognizant.taleo.net' + p);
    const preview = r.body.substring(0, 150);
    console.log('[' + r.status + '] ' + p.substring(0, 50) + ' CT:' + (r.ct||'').substring(0,30) + ' | ' + preview.replace(/\n/g,' '));
  }
  
  console.log('\n=== Taleo REST API for Cognizant ===');
  // Modern Taleo REST API
  const r2 = await get('https://cognizant.taleo.net/careersection/10000/jobsearch.ftl?lang=en&portal=true&format=json');
  console.log('[' + r2.status + '] JSON API:', r2.body.substring(0, 400));
  
  // Check what Taleo companies use - look for the jobsearch REST API
  const r3 = await get('https://cognizant.taleo.net/careersection/10001/jobsearch.ftl?lang=en&format=json&act=getResults&no_retries=false');
  console.log('[' + r3.status + '] Taleo results format:', r3.body.substring(0, 400));
}

main().catch(console.error);
