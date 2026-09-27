// Test Darwinbox API endpoints properly
const https = require('https');

function postJson(hostname, path, data) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify(data);
    const opts = {
      hostname, path, method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body),
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
        'Accept': 'application/json',
        'Referer': `https://${hostname}/`,
        'Origin': `https://${hostname}`
      }
    };
    const req = https.request(opts, res => {
      let b = '';
      res.on('data', c => b += c);
      res.on('end', () => resolve({ status: res.statusCode, body: b }));
    });
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

function getJson(hostname, path) {
  return new Promise((resolve, reject) => {
    const opts = {
      hostname, path, method: 'GET',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
        'Accept': 'application/json',
        'Referer': `https://${hostname}/`,
      }
    };
    https.get({ ...opts }, res => {
      let b = '';
      res.on('data', c => b += c);
      res.on('end', () => resolve({ status: res.statusCode, body: b }));
    }).on('error', reject);
  });
}

(async () => {
  // Darwinbox - try various API endpoints
  const darwinboxEndpoints = [
    ['/ms/candidatev2/candidateportal/getjoblist', { page_no: 1, emp_id: '' }],
    ['/ms/candidatev2/candidateportal/listjobs', { page: 1, limit: 10 }],
    ['/ms/candidatev2/main/careers/getjoblist', { page: 1 }],
    ['/ms/candidatev2/getjoblist', { page: 1 }],
  ];
  
  for (const [path, data] of darwinboxEndpoints) {
    const res = await postJson('airtel.darwinbox.in', path, data).catch(e => ({ status: 'err', body: e.message }));
    console.log(`[${res.status}] POST ${path}: ${res.body.substring(0, 100)}`);
  }
  
  // LG - try their API  
  const lgEndpoints = [
    '/api/recruit/jobList',
    '/api/v1/jobList',
    '/api/jobs?page=1&size=10',
    '/api/recruit/jobs?page=1&size=10',
  ];
  for (const path of lgEndpoints) {
    const res = await getJson('globalcareers.lge.com', path).catch(e => ({ status: 'err', body: e.message }));
    console.log(`[${res.status}] GET ${path}: ${res.body.substring(0, 150)}`);
  }
})();
