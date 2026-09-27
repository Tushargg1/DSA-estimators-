// Check what hrefs Airtel and LG actually return (they're SPAs so HTML is a shell)
const https = require('https');

function fetchUrl(url) {
  return new Promise((resolve, reject) => {
    https.get(url, { 
      headers: { 
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
        'Accept': 'text/html',
      }
    }, res => {
      let body = '';
      res.on('data', c => body += c);
      res.on('end', () => resolve({ status: res.statusCode, body }));
    }).on('error', reject);
  });
}

(async () => {
  // Check Airtel - what hrefs does it have?
  const airtel = await fetchUrl('https://airtel.darwinbox.in/ms/candidatev2/main/careers/allJobs');
  console.log("=== AIRTEL hrefs ===");
  const airtelHrefs = [...airtel.body.matchAll(/href="([^"]+)"/gi)].map(m => m[1]);
  console.log(airtelHrefs);
  
  // Check LG - what hrefs does it have?  
  const lg = await fetchUrl('https://globalcareers.lge.com/jobs');
  console.log("\n=== LG hrefs ===");
  const lgHrefs = [...lg.body.matchAll(/href="([^"]+)"/gi)].map(m => m[1]);
  console.log(lgHrefs);
  
  // Also check if there's a __NEXT_DATA__ or embedded JSON in LG
  const nextDataMatch = lg.body.match(/<script id="__NEXT_DATA__"[^>]*>([\s\S]*?)<\/script>/);
  if (nextDataMatch) {
    console.log("\n=== LG __NEXT_DATA__ (first 500) ===");
    console.log(nextDataMatch[1].substring(0, 500));
  }
})();
