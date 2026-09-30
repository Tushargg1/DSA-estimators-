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
  console.log('=== SmartRecruiters - real company slugs ===');
  const srCos = ['wipro', 'hcltech', 'mphasis', 'cognizant', 'infosys', 'tcs-tata-consultancy-services'];
  for (const c of srCos) {
    const r = await get('api.smartrecruiters.com', '/v1/companies/' + c + '/postings?limit=3');
    const preview = r.body.substring(0, 150);
    console.log('[' + r.status + '] SR/' + c + ':', preview);
  }
  
  console.log('\n=== SAP SuccessFactors (career.sap.com) ===');
  const r = await get('www.sap.com', '/careers/en/search.html?format=json');
  console.log('[' + r.status + '] SAP careers JSON:', r.body.substring(0, 200));
  
  console.log('\n=== iCIMS API test ===');
  // iCIMS companies need their own tenant ID, usually subdomain
  // HCL: https://hcltech.icims.com  
  // Cognizant: https://cognizant.taleo.net
  const r2 = await get('hcltech.icims.com', '/jobs/search?pr=1&format=json&in=1');
  console.log('[' + r2.status + '] iCIMS HCL:', r2.body.substring(0, 200));
}

main().catch(console.error);
