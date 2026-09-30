const https = require('https');

function get(url) {
  return new Promise((resolve) => {
    const parsed = new URL(url);
    const r = https.request({
      hostname: parsed.hostname, path: parsed.pathname + parsed.search, method: 'GET',
      headers: { 'User-Agent': 'Mozilla/5.0', 'Accept': 'application/json, text/html, */*' }
    }, (res) => {
      let b = ''; res.on('data', c => b += c);
      res.on('end', () => resolve({ status: res.statusCode, ct: res.headers['content-type'], location: res.headers['location'], body: b }));
    });
    r.on('error', e => resolve({ status: 'ERR', body: e.message }));
    r.end();
  });
}

async function main() {
  console.log('=== iCIMS API (needs tenant ID) ===');
  // iCIMS API: https://{tenant}.icims.com/jobs/search?pr=1&format=json
  // Typical tenant IDs for Indian companies
  const icimsTenants = [
    'cognizant.icims.com',  // Cognizant
    'hcl.icims.com',
    'wipro.icims.com',
    'capgemini.icims.com',
  ];
  for (const t of icimsTenants) {
    const url = 'https://' + t + '/jobs/search?pr=1&format=json&in=1';
    const r = await get(url).catch(e => ({ status: 'ERR', body: e.message }));
    console.log('[' + r.status + '] ' + t + ':', (r.body || '').substring(0, 150));
  }

  console.log('\n=== Workday - correct Infosys/Wipro slugs ===');
  // Try finding real Workday slugs for Infosys and Wipro
  // Common pattern: myworkdayjobs.com/Infosys_Careers or similar
  const wdSlugs = [
    'https://infosys.wd3.myworkdayjobs.com/Infosys_Careers',
    'https://infosys.wd3.myworkdayjobs.com/en-US/Infosys_Careers',
    'https://wipro.wd3.myworkdayjobs.com/en-US/Wipro_External_Careers',
    'https://hcltech.wd3.myworkdayjobs.com/en-US/HCL_Careers',
  ];
  for (const slug of wdSlugs) {
    const parsed = new URL(slug);
    const body = JSON.stringify({ appliedFacets: {}, limit: 2, offset: 0, searchText: '' });
    // Extract tenant and site from URL
    const parts = parsed.pathname.split('/').filter(Boolean);
    const site = parts[parts.length - 1]; // last part after locale
    const tenant = parsed.hostname.split('.')[0];
    const apiPath = '/wday/cxs/' + tenant + '/' + site + '/jobs';
    
    const r = await new Promise((resolve) => {
      const h = {
        'User-Agent': 'Mozilla/5.0', 'Accept': 'application/json',
        'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body).toString()
      };
      const req = https.request({ hostname: parsed.hostname, path: apiPath, method: 'POST', headers: h }, res => {
        let b = ''; res.on('data', c => b += c);
        res.on('end', () => resolve({ status: res.statusCode, body: b }));
      });
      req.on('error', e => resolve({ status: 'ERR', body: e.message }));
      req.write(body); req.end();
    });
    console.log('[' + r.status + '] POST ' + parsed.hostname + apiPath + ':', r.body.substring(0, 150));
  }

  console.log('\n=== SAP SuccessFactors external career portals ===');
  // SAP SF companies: Siemens, DHL, etc.
  // SF career portal API: https://{host}/api/rest/jobseeking/jobs?count=20&offset=0
  const sfTests = [
    'https://performancemanager.successfactors.com/career?company=1SAP',
    'https://siemens.wd3.myworkdayjobs.com/wday/cxs/siemens/Siemens/jobs',
  ];
  for (const url of sfTests) {
    const r = await get(url);
    console.log('[' + r.status + '] GET ' + url.substring(0, 60) + ':', r.body.substring(0, 100));
  }
}

main().catch(console.error);
