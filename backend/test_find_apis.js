// Find what XHR/fetch calls LG's app makes
// LG Next.js app - we need to find the API route
// Their API likely has a pattern like /api/jobs or similar

const https = require('https');

function get(hostname, path, headers = {}) {
  return new Promise((resolve, reject) => {
    https.get({ hostname, path, headers: { 'Accept': 'application/json', 'User-Agent': 'Mozilla/5.0', ...headers } }, res => {
      let b = '';
      res.on('data', c => b += c);
      res.on('end', () => resolve({ status: res.statusCode, body: b }));
    }).on('error', reject);
  });
}

function post(hostname, path, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const buf = Buffer.from(JSON.stringify(body));
    const req = https.request({ 
      hostname, path, method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json', 'Content-Length': buf.length, 'User-Agent': 'Mozilla/5.0', ...headers }
    }, res => {
      let b = '';
      res.on('data', c => b += c);
      res.on('end', () => resolve({ status: res.statusCode, body: b }));
    });
    req.on('error', reject);
    req.write(buf);
    req.end();
  });
}

(async () => {
  // LG Next.js - check their _next API routes
  console.log('=== LG NEXT.JS API ROUTES ===');
  const lgPaths = [
    '/api/jobs',
    '/api/jobs/list', 
    '/api/recruit',
    '/api/recruit/list',
    '/api/search',
    '/api/job-list',
  ];
  for (const p of lgPaths) {
    const r = await get('globalcareers.lge.com', p).catch(e => ({status:'ERR',body:e.message}));
    console.log(`[${r.status}] GET ${p}: ${r.body.substring(0,100)}`);
  }

  // LG POST endpoints
  const lgPostPaths = [
    ['/api/jobs', { page: 1, size: 10 }],
    ['/api/recruit/list', { page: 1, pageSize: 10, country: 'India' }],
  ];
  for (const [p, body] of lgPostPaths) {
    const r = await post('globalcareers.lge.com', p, body).catch(e => ({status:'ERR',body:e.message}));
    console.log(`[${r.status}] POST ${p}: ${r.body.substring(0,100)}`);
  }

  // KPMG TalentRecruit - find their API
  console.log('\n=== KPMG TALENTRECRUIT API ===');
  // Check the JS bundle for API URLs
  const kpmgHtml = await get('kpmgindia.talentrecruit.com', '/career-page', { 'Referer': 'https://www.google.com/' });
  const scriptMatches = kpmgHtml.body.match(/src="([^"]+\.js[^"]*)"/g);
  console.log('KPMG scripts:', scriptMatches?.slice(0,5));
  
  // TalentRecruit standard API pattern  
  const trPaths = [
    '/portal/getAllJobs',
    '/api/v1/jobs',
    '/api/jobs/list',
    '/portal/jobSearch',
  ];
  for (const p of trPaths) {
    const r = await get('kpmgindia.talentrecruit.com', p, { 'Referer': 'https://kpmgindia.talentrecruit.com/career-page' }).catch(e => ({status:'ERR',body:e.message}));
    console.log(`[${r.status}] GET ${p}: ${r.body.substring(0,100)}`);
  }
})();
