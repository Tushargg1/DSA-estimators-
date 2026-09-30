const https = require('https');

function get(url, extraHeaders = {}) {
  return new Promise((resolve) => {
    const parsed = new URL(url);
    const r = https.request({
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

async function main() {
  // iCIMS: The actual tenant IDs are numeric, e.g. https://jobs.icims.com/jobs/5/job
  // Their search API: GET https://{company}.icims.com/jobs/search?pr=1&format=json&iis=&iisn=&in=1&ss=
  // But we need the actual subdomain

  // Try Cognizant's actual iCIMS URL
  console.log('=== iCIMS - search format ===');
  const r1 = await get('https://cognizant.icims.com/jobs/search?pr=1&format=json&in=1');
  console.log('[' + r1.status + '] Cognizant iCIMS search:', r1.body.substring(0, 200), 'Location:', r1.location);

  // Try with known iCIMS customer IDs  
  // iCIMS uses numeric IDs in their API too
  const r2 = await get('https://careers.icims.com/jobs/search?pr=1&format=json&in=1');
  console.log('[' + r2.status + '] careers.icims.com search:', r2.body.substring(0, 200));

  // Try Oracle Taleo API - it's common in India
  console.log('\n=== Oracle Taleo ===');
  // Common Taleo companies: HCL, Cognizant, L&T
  const taleoCos = ['cognizant', 'hcl', 'lnt', 'mahindra', 'byjus'];
  for (const c of taleoCos) {
    const r = await get('https://' + c + '.taleo.net/careersection/10003/jobsearch.ftl?lang=en&format=json').catch(e => ({ status: 'ERR', body: e.message }));
    console.log('[' + r.status + '] Taleo ' + c + ':', r.body?.substring(0, 100) || r.location || 'N/A');
  }

  console.log('\n=== Naukri Jobs API (commonly used in India) ===');
  const r3 = await get('https://www.naukri.com/jobapi/v3/search?noOfResults=2&urlType=search_by_key_loc&searchType=adv&keyword=software+engineer&location=&pageNo=1', {
    'Appid': '109'
  });
  console.log('[' + r3.status + '] Naukri API:', r3.body.substring(0, 300));
}

main().catch(console.error);
