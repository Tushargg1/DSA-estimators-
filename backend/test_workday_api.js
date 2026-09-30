const fetch = require('node-fetch');

async function test() {
    const tenant = "ncr";
    const wdInstance = "wd1";
    const site = "ext_apac";
    const url = `https://${tenant}.${wdInstance}.myworkdayjobs.com/wday/cxs/${tenant}/${site}/jobs`;
    
    console.log("Fetching: " + url);
    const body = {
        appliedFacets: {},
        limit: 20,
        offset: 0,
        searchText: ""
    };
    
    const res = await fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json'
        },
        body: JSON.stringify(body)
    });
    
    console.log("Status: " + res.status);
    const text = await res.text();
    console.log(text.substring(0, 500));
}

test();
