import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const html = readFileSync(resolve(root, 'app/src/main/assets/tester.html'), 'utf8');
const kotlin = readFileSync(resolve(root, 'app/src/main/java/ir/behnamghafari/apitester/MainActivity.kt'), 'utf8');
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
const unlabeledInputs = [...html.matchAll(/<input\b[^>]*>/gi)].filter((m) => !/\b(?:aria-label|id)=/.test(m[0]));

assert(duplicates.length === 0, 'Duplicate HTML IDs: ' + duplicates.join(', '));
assert(unlabeledInputs.length === 0, 'Inputs missing accessible labels: ' + unlabeledInputs.length);
assert(html.includes('id="appVersion"'), 'Dynamic version label is missing from About');
assert(html.includes('BehnamNative.openPrivacyPolicy()'), 'Privacy policy link is missing from About');
assert(kotlin.includes('BuildConfig.VERSION_NAME'), 'Native app version bridge is missing');
assert(kotlin.includes('/PRIVACY.md'), 'Privacy policy URL is missing');
assert(!/user-scalable\s*=\s*no/i.test(html), 'Viewport zoom must remain available for accessibility');
assert(!/<(?:script|link|img)\b[^>]+(?:src|href)=["']https?:\/\//i.test(html), 'External runtime assets are not allowed');
assert(html.includes('id="allowScripts"'), 'Explicit script permission control is missing');
assert(html.includes("if(activeRequestScriptsAllowed)runScript($('preScript').value,context)"), 'Pre-request script permission guard is missing');
assert(html.includes("if(activeRequestScriptsAllowed)runScript($('testScript').value,ctx)"), 'Test script permission guard is missing');
assert(html.includes('req:{...c.req,allowScripts:false}'), 'Imported scripts must be disabled by default');
assert(html.includes("if(mode==='graphql')sendGraphQL();else sendRequest()"), 'GraphQL collection runner routing is missing');
assert(html.includes("const mode=x.mode||x.req.mode||'rest'"), 'Saved request mode restoration is missing');
assert(html.includes("r.id!==$('pendingId').value"), 'Stale response protection is missing');
assert(html.includes('if(r.id!==scenarioRun.currentRequestId)return'), 'Scenario response correlation is missing');
assert(html.includes('run.currentRequestId=req.id'), 'Scenario request ID tracking is missing');
assert(html.includes("درخواست قبلی هنوز در حال اجراست"), 'Overlapping request guard is missing');
assert(html.includes('savedResponse.body.length>128*1024'), 'Stored response history must be bounded');
assert(html.includes('function addHistory(req,res)'), 'Request history storage function is missing');
assert(html.includes("x.req?.mode!=='websocket'"), 'WebSocket requests must be excluded from REST collection runs');
assert(html.includes("req:{method,url,allowScripts:true,params:[]"), 'Built-in sample assertions must remain enabled');
assert(html.includes("wsHistory=wsHistory.slice(0,200)"), 'WebSocket history count must be bounded');
assert(html.includes("const maxChars=32*1024"), 'WebSocket history payloads must be bounded');
assert(kotlin.includes('val maxChars = 32 * 1024'), 'Native WebSocket message forwarding must be bounded');
assert(kotlin.includes('val completedInTime = latch.await(180, TimeUnit.SECONDS)'), 'Load test timeout state must be recorded');
assert(kotlin.includes('.put("notCompleted", notCompleted)'), 'Load test incomplete requests must be reported');

console.log('ApiX static validation passed: ' + scripts.length + ' JavaScript block, ' + ids.length + ' unique HTML IDs, ' + unlabeledInputs.length + ' unlabeled inputs, no external runtime assets, and release-critical safety checks.');
