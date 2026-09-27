// Test what URLs look like for common career sites and whether our JOB_URL_PATTERN matches them
const urls = [
  // Darwinbox - airtel (we know this works with Playwright)
  "/ms/candidatev2/main/careers/jobDetails/65e1ed00db518",
  "/ms/candidatev2/main/careers/jobDetails/a6a31276ddb94c",
  // LG  
  "https://globalcareers.lge.com/jobs",
  "https://globalcareers.lge.com/jobs/12345-software-engineer",
  // KPMG TalentRecruit
  "/jobs/view/12345",
  "/job-openings/12345",
  // Generic patterns
  "https://company.com/en/careers/roles/12345",
  "https://company.com/about-us/join-us",
  "https://company.com/work-with-us",
  "https://company.com/open-positions/engineer",
  "https://company.com/current-openings/engineer",
];

const JOB_URL_PATTERN = /\/job|\/career|\/apply|\/position|\/opening|\/vacanc|\/req|\/role|\/opportunity|\/posting|\/detail/i;

console.log("=== Testing JOB_URL_PATTERN against URLs ===");
for (const url of urls) {
  const matched = JOB_URL_PATTERN.test(url);
  console.log(`[${matched ? 'MATCH' : 'MISS '}] ${url}`);
}

// The Airtel Darwinbox links have /careers/jobDetails - let's check
// /careers -> matches /career ✓
// /jobDetails -> matches /job ✓  

// What about common patterns we're MISSING?
console.log("\n=== Patterns that SHOULD match but might not ===");
const missed = [
  "https://company.com/work-with-us/engineer",       // no keyword
  "https://company.com/join-us",                      // no keyword  
  "https://company.com/opportunities",               // matches /opportunit -> YES
  "https://company.com/join/software-engineer",       // no keyword
  "https://hiring.company.com/software-engineer",    // hiring subdomain
  "https://company.com/vacancies",                   // /vacanc -> YES
  "https://company.com/current-openings",            // /opening -> YES
  "https://company.com/open-positions",              // /position -> YES
];

for (const url of missed) {
  const matched = JOB_URL_PATTERN.test(url);
  console.log(`[${matched ? 'MATCH' : 'MISS '}] ${url}`);
}
