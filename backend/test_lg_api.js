// Test LG API with body/headers
const https = require('https');

function postJson(hostname, path, data, extraHeaders = {}) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify(data);
    const req = https.request({
      hostname, path, method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body),
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
        'Accept': 'application/json',
        'Referer': `https://${hostname}/`,
        'Origin': `https://${hostname}`,
        ...extraHeaders
      }
    }, res => {
      let b = '';
      res.on('data', c => b += c);
      res.on('end', () => resolve({ status: res.statusCode, body: b }));
    });
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

(async () => {
  // LG API - the 500 means the endpoint exists but body params are wrong
  // Try different params
  const lgTests = [
    ['/api/recruit/jobList', { pageNo: 1, pageSize: 10 }],
    ['/api/recruit/jobList', { page: 1, size: 10, countryCd: 'IN' }],
    ['/api/recruit/jobList', { pageNo: 1, pageSize: 10, countryCd: 'IN', langCd: 'EN' }],
    ['/api/recruit/jobList', { pageNo: 1, pageSize: 10, searchCountry: 'IN' }],
    ['/api/recruit/jobList', {}],
    ['/api/recruit/jobs', { pageNo: 1, pageSize: 10 }],
  ];
  
  for (const [path, data] of lgTests) {
    const res = await postJson('globalcareers.lge.com', path, data).catch(e => ({ status: 'err', body: e.message }));
    console.log(`[${res.status}] POST ${path} ${JSON.stringify(data)}: ${res.body.substring(0, 200)}`);
  }
})();
