const fs = require("fs");

const file = process.argv[2] || "report.json";

if (!fs.existsSync(file)) {
  console.error("Report file not found:", file);
  process.exit(1);
}

let raw = fs.readFileSync(file, "utf8");

/*
Playwright json reporter 有时候前面会混入日志
例如:
🧹 Running global teardown...
所以需要找到真正 JSON 开头
*/
const start = raw.indexOf("{");
if (start > 0) {
  raw = raw.slice(start);
}

const report = JSON.parse(raw);

const badStatuses = new Set([
  "failed",
  "timedOut",
  "interrupted",
  "skipped",
]);

const results = [];

function walkSuite(suite) {

  if (suite.specs) {
    for (const spec of suite.specs) {

      const tests = spec.tests || [];

      for (const test of tests) {

        const testResults = test.results || [];

        if (testResults.length === 0) {
          results.push({
            id: spec.id,
            title: spec.title,
            file: spec.file,
            status: "notRun"
          });
          continue;
        }

        for (const r of testResults) {
          if (badStatuses.has(r.status)) {
            results.push({
              id: spec.id,
              title: spec.title,
              file: spec.file,
              status: r.status
            });
          }
        }

      }
    }
  }

  if (suite.suites) {
    for (const s of suite.suites) {
      walkSuite(s);
    }
  }
}

for (const s of report.suites) {
  walkSuite(s);
}

const summary = {};

for (const r of results) {
  summary[r.status] = (summary[r.status] || 0) + 1;
}

console.log("\n=== BAD TEST SUMMARY ===");
console.log(summary);

console.log("\n=== BAD TESTS ===");

for (const r of results) {
  console.log(
    `${r.status.padEnd(10)} | ${r.title} | ${r.file}`
  );
}

console.log("\nTotal bad tests:", results.length);