const https = require('https');

function get(host, path, extraHeaders = {}) {
  return new Promise((resolve) => {
    const headers = {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
      'Accept': 'application/json, text/plain, */*',
      ...extraHeaders
    };
    const r = https.request({ hostname: host, path, method: 'GET', headers }, (res) => {
      let b = '';
      res.on('data', c => b += c);
      res.on('end', () => resolve({ status: res.statusCode, ct: res.headers['content-type'], body: b }));
    });
    r.on('error', e => resolve({ status: 'ERR', body: e.message }));
    r.end();
  });
}

function post(host, path, bodyObj, extraHeaders = {}) {
  const body = JSON.stringify(bodyObj);
  return new Promise((resolve) => {
    const headers = {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
      'Accept': 'application/json',
      'Content-Type': 'application/json',
      'Content-Length': Buffer.byteLength(body).toString(),
      ...extraHeaders
    };
    const r = https.request({ hostname: host, path, method: 'POST', headers }, (res) => {
      let b = '';
      res.on('data', c => b += c);
      res.on('end', () => resolve({ status: res.statusCode, ct: res.headers['content-type'], body: b }));
    });
    r.on('error', e => resolve({ status: 'ERR', body: e.message }));
    r.write(body);
    r.end();
  });
}

async function main() {
  console.log('=== SmartRecruiters (SR) ===');
  // SmartRecruiters public API: https://api.smartrecruiters.com/v1/companies/{company}/postings
  const srCompanies = ['Infosys', 'TCS', 'Wipro', 'HCLTech'];
  for (const c of srCompanies) {
    const r = await get('api.smartrecruiters.com', '/v1/companies/' + c + '/postings?limit=3');
    console.log('[' + r.status + '] SmartRecruiters/' + c + ' - CT:', r.ct?.substring(0,40), '- Body:', r.body.substring(0, 200));
  }

  console.log('\n=== iCIMS ===');
  // iCIMS: typically at https://{company}.icims.com/jobs/search
  const r2 = await get('careers.icims.com', '/jobs/search?pr=1&format=json&hireType=&sortBy=&sortOrder=&d=a&iis=&iisn=&in=1');
  console.log('[' + r2.status + '] iCIMS search - CT:', r2.ct?.substring(0,40), '- Body:', r2.body.substring(0, 200));

  console.log('\n=== Taleo (Oracle) ===');
  // Taleo: https://{company}.taleo.net/careersection/{section}/jobsearch.ftl?lang=en
  // REST: https://{company}.taleo.net/careersection/jobsearch.ftl?lang=en&format=json
  const r3 = await get('tcs.taleo.net', '/careersection/jobsearch.ftl?lang=en&format=json');
  console.log('[' + r3.status + '] Taleo TCS - CT:', r3.ct?.substring(0,40), '- Body:', r3.body.substring(0, 200));
}

main().catch(console.error);
