const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync(require('node:path').join(__dirname, '..', 'assets', 'tester.html'), 'utf8');
let count = 0;
for (const match of html.matchAll(/<script\\b([^>]*)>([\\s\\S]*?)<\\/script>/gi)) {
  if (/\\bsrc\\s*=/.test(match[1])) continue;
  const source = match[2].trim();
  if (!source) continue;
  new vm.Script(source, { filename: 'tester.html:inline-script-' + (++count) });
}
if (!count) throw new Error('No inline scripts found to validate');
console.log('Validated ' + count + ' inline JavaScript block(s).');
