const https = require('https');

function req(host, path, method = 'GET', body = null) {
  return new Promise((resolve) => {
    const headers = {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
      'Accept': 'application/json',
      'X-Requested-With': 'XMLHttpRequest',
      'Referer': 'https://' + host + '/ms/candidatev2/main/careers/allJobs'
    };
    if (body) {
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = Buffer.byteLength(body).toString();
    }
    const r = https.request({ hostname: host, path, method, headers }, (res) => {
      let b = '';
      res.on('data', c => b += c);
      res.on('end', () => resolve({ status: res.statusCode, ct: res.headers['content-type'], body: b }));
    });
    r.on('error', e => resolve({ status: 'ERR', body: e.message }));
    if (body) r.write(body);
    r.end();
  });
}

async function main() {
  const tests = [
    { host: 'airtel.darwinbox.in', path: '/ms/candidatev2/main/careers/getJobOpenings' },
    { host: 'airtel.darwinbox.in', path: '/ms/candidatev2/main/careers/getAllJobs?page=1&per_page=10' },
    { host: 'airtel.darwinbox.in', path: '/ms/candidatev2/main/careers/getActiveJobOpenings' },
    { host: 'zomato.darwinbox.in', path: '/ms/candidatev2/main/careers/getJobOpenings' },
  ];

  for (const t of tests) {
    const r = await req(t.host, t.path);
    const preview = (r.body || '').substring(0, 300);
    console.log('[' + r.status + '] GET ' + t.host + t.path);
    console.log('  CT:', r.ct);
    console.log('  Body:', preview);
    console.log();
  }
}

main().catch(console.error);
