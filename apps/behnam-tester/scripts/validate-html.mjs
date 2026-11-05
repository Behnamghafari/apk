import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const html = readFileSync(resolve(root, 'app/src/main/assets/tester.html'), 'utf8');
const scripts = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].map((m) => m[1]);

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

assert(scripts.length === 1, 'Expected one inline application script, found ' + scripts.length);
for (const [index, source] of scripts.entries()) {
  try {
    new Function(source);
  } catch (error) {
    throw new Error('JavaScript block ' + (index + 1) + ' has a syntax error: ' + error.message);
  }
}

const ids = [...html.matchAll(/\bid=["']([^"']+)["']/g)].map((m) => m[1]);
const duplicates = [...new Set(ids.filter((id, index) => ids.indexOf(id) !== index))];
assert(duplicates.length === 0, 'Duplicate HTML IDs: ' + duplicates.join(', '));
assert(!/user-scalable\s*=\s*no/i.test(html), 'Viewport zoom must remain available for accessibility');
assert(!/<(?:script|link|img)\b[^>]+(?:src|href)=["']https?:\/\//i.test(html), 'External runtime assets are not allowed');
assert(html.includes('id="allowScripts"'), 'Explicit script permission control is missing');
assert(html.includes("if(activeRequestScriptsAllowed)runScript($('preScript').value,context)"), 'Pre-request script permission guard is missing');
assert(html.includes("if(activeRequestScriptsAllowed)runScript($('testScript').value,ctx)"), 'Test script permission guard is missing');
assert(html.includes('req:{...c.req,allowScripts:false}'), 'Imported scripts must be disabled by default');
assert(html.includes("if(mode==='graphql')sendGraphQL();else sendRequest()"), 'GraphQL collection runner routing is missing');
assert(html.includes("const mode=x.mode||x.req.mode||'rest'"), 'Saved request mode restoration is missing');
assert(html.includes("r.id!==$('pendingId').value"), 'Stale response protection is missing');
assert(html.includes("x.req?.mode!=='websocket'"), 'WebSocket requests must be excluded from REST collection runs');

console.log('ApiX static validation passed: ' + scripts.length + ' JavaScript block, ' + ids.length + ' unique HTML IDs, no external runtime assets, and release-critical safety checks.');
