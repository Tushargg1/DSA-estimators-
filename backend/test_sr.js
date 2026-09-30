const https = require('https');

function get(url) {
  return new Promise((resolve) => {
    const parsed = new URL(url);
    const r = https.request({
      hostname: parsed.hostname, path: parsed.pathname + parsed.search, method: 'GET',
      headers: { 'User-Agent': 'Mozilla/5.0', 'Accept': 'application/json' }
    }, (res) => {
      let b = ''; res.on('data', c => b += c);
      res.on('end', () => resolve({ status: res.statusCode, body: b }));
    });
    r.on('error', e => resolve({ status: 'ERR', body: e.message }));
    r.end();
  });
}

async function main() {
  console.log('=== SmartRecruiters BoschGroup sample ===');
  const r = await get('https://api.smartrecruiters.com/v1/companies/BoschGroup/postings?limit=2');
  const data = JSON.parse(r.body);
  console.log('totalFound:', data.totalFound);
  if (data.content && data.content.length > 0) {
    const job = data.content[0];
    console.log('Sample job keys:', Object.keys(job).join(', '));
    console.log('Sample job:', JSON.stringify(job, null, 2).substring(0, 1000));
  }

  console.log('\n=== SmartRecruiters - jobUrl format ===');
  const r2 = await get('https://api.smartrecruiters.com/v1/companies/BoschGroup/postings?limit=1&offset=0');
  const d2 = JSON.parse(r2.body);
  if (d2.content && d2.content.length > 0) {
    const j = d2.content[0];
    console.log('id:', j.id);
    console.log('uuid:', j.uuid);
    console.log('name:', j.name);
    console.log('ref:', j.ref);
    console.log('refNumber:', j.refNumber);
    console.log('location:', JSON.stringify(j.location));
    console.log('department:', JSON.stringify(j.department));
    console.log('typeOfEmployment:', JSON.stringify(j.typeOfEmployment));
    console.log('industry:', JSON.stringify(j.industry));
    console.log('experienceLevel:', JSON.stringify(j.experienceLevel));
    console.log('releasedDate:', j.releasedDate);
    console.log('jobPageUrl (constructed):', 'https://careers.smartrecruiters.com/BoschGroup/' + j.uuid);
  }

  console.log('\n=== Verify job page URL ===');
  if (d2.content && d2.content.length > 0) {
    const j = d2.content[0];
    const url = 'https://careers.smartrecruiters.com/BoschGroup/' + j.uuid;
    const r3 = await get(url);
    console.log('[' + r3.status + '] ' + url);
    console.log('Body preview:', r3.body.substring(0, 200));
  }
}

main().catch(console.error);
