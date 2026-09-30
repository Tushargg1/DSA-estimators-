const https = require('https');

function get(url, extraHeaders = {}) {
  return new Promise((resolve) => {
    const parsed = new URL(url);
    const headers = {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
      'Accept': 'application/json, text/html, */*',
      ...extraHeaders
    };
    const r = https.request({ hostname: parsed.hostname, path: parsed.pathname + parsed.search, method: 'GET', headers }, (res) => {
      let b = '';
      res.on('data', c => b += c);
      res.on('end', () => resolve({ status: res.statusCode, ct: res.headers['content-type'], location: res.headers['location'], body: b }));
    });
    r.on('error', e => resolve({ status: 'ERR', body: e.message }));
    r.end();
  });
}

async function main() {
  console.log('=== SmartRecruiters - TCS correct slug ===');
  const slugs = ['TataConsultancyServices', 'TCS', 'tata-consultancy-services'];
  for (const s of slugs) {
    const r = await get('https://api.smartrecruiters.com/v1/companies/' + s + '/postings?limit=2');
    console.log('[' + r.status + '] SR/' + s + ':', r.body.substring(0, 150));
  }

  console.log('\n=== SmartRecruiters - actual working companies ===');
  // These companies are known to use SmartRecruiters:
  const knownSR = ['McDonald', 'IKEA', 'Verizon', 'BoschGroup'];
  for (const s of knownSR) {
    const r = await get('https://api.smartrecruiters.com/v1/companies/' + s + '/postings?limit=2');
    console.log('[' + r.status + '] SR/' + s + ':', r.body.substring(0, 150));
  }

  console.log('\n=== Workday - major Indian companies ===');
  // TCS iBegin / Accenture already handled
  // Let's try Infosys Workday
  const wdTests = [
    'https://infosys.wd3.myworkdayjobs.com/wday/cxs/infosys/Infosys/jobs',
    'https://wipro.wd3.myworkdayjobs.com/wday/cxs/wipro/Wipro_External_Careers/jobs',
  ];
  for (const url of wdTests) {
    const parsed = new URL(url);
    const body = JSON.stringify({ appliedFacets: {}, limit: 2, offset: 0, searchText: '' });
    const r = await new Promise((resolve) => {
      const headers = {
        'User-Agent': 'Mozilla/5.0', 'Accept': 'application/json',
        'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body).toString()
      };
      const req = https.request({ hostname: parsed.hostname, path: parsed.pathname, method: 'POST', headers }, res => {
        let b = ''; res.on('data', c => b += c);
        res.on('end', () => resolve({ status: res.statusCode, body: b }));
      });
      req.on('error', e => resolve({ status: 'ERR', body: e.message }));
      req.write(body); req.end();
    });
    console.log('[' + r.status + '] POST ' + url + ':', r.body.substring(0, 200));
  }

  console.log('\n=== SAP SuccessFactors ===');
  // SAP SuccessFactors career sites
  const sfTests = [
    'https://jobs.sap.com/search/?q=&locationsearch=india&iis=LinkedIn&iisn=LinkedIn',
    'https://api.sap.com/api/External_Job_Postings/get-jobs',
  ];
  for (const url of sfTests) {
    const r = await get(url);
    console.log('[' + r.status + '] GET ' + url.substring(0, 60) + ':', r.body.substring(0, 200));
  }
}

main().catch(console.error);
