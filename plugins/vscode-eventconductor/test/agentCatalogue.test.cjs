const { test } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { catalogueEndpoint, fetchAgentCatalogue, validateCatalogue } = require('../out/agentCatalogue');

async function serve(t, handler) {
  const server = http.createServer(handler);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  });
  return `http://127.0.0.1:${server.address().port}/api/workflow-agents`;
}

test('catalogue endpoint rejects embedded credentials and non-HTTP URLs', () => {
  assert.equal(catalogueEndpoint('https://example.test/api/workflow-agents').protocol, 'https:');
  for (const url of ['not-a-url', 'file:///tmp/catalogue', 'https://user:secret@example.test/api', 'https://example.test/api#fragment']) {
    assert.throws(() => catalogueEndpoint(url));
  }
});

test('catalogue projects capabilities without exposing prompts, model, or other fields', () => {
  assert.deepEqual(validateCatalogue([{
    id: 'assessor', name: 'Case assessor', mcp: ['cases', 'cases'], rag: null, peers: ['evidence'],
    systemPrompt: 'private prompt', llm: { apiKey: 'private-key' }, authorization: 'private-token',
  }]), [{ id: 'assessor', name: 'Case assessor', mcp: ['cases'], peers: ['evidence'] }]);
  for (const value of [{}, [null], [{ id: '' }], [{ id: 'a', mcp: [{}] }], [{ id: 'a' }, { id: 'a' }]]) {
    assert.throws(() => validateCatalogue(value));
  }
});

test('extension-host HTTP reads catalogue with Basic auth and returns only metadata', async t => {
  const authorization = 'Basic ' + Buffer.from('local-operator:synthetic-password').toString('base64');
  let observed;
  const url = await serve(t, (request, response) => {
    observed = { method: request.method, path: request.url, authorization: request.headers.authorization };
    response.end(JSON.stringify([{ id: 'assessor', mcp: ['cases'], systemPrompt: 'private' }]));
  });
  assert.deepEqual(await fetchAgentCatalogue(url, authorization), [{ id: 'assessor', mcp: ['cases'] }]);
  assert.deepEqual(observed, { method: 'GET', path: '/api/workflow-agents', authorization });
});

test('authentication and redirects have actionable errors and never forward credentials', async t => {
  let destinationRequests = 0;
  const destination = await serve(t, (_, response) => { destinationRequests++; response.end('[]'); });
  const redirect = await serve(t, (_, response) => { response.writeHead(302, { Location: destination }); response.end(); });
  await assert.rejects(fetchAgentCatalogue(redirect, 'Bearer synthetic-token'), /redirects are not followed/);
  assert.equal(destinationRequests, 0);
  for (const status of [401, 403]) {
    const denied = await serve(t, (_, response) => { response.writeHead(status); response.end('secret server details'); });
    await assert.rejects(fetchAgentCatalogue(denied), /Configure Agent Catalogue/);
  }
});

test('HTTP response must be valid JSON with the catalogue contract', async t => {
  for (const body of ['<html>login</html>', '{"items":[]}', '[{"id":"a","rag":123}]']) {
    const url = await serve(t, (_, response) => response.end(body));
    await assert.rejects(fetchAgentCatalogue(url), /JSON|catalogue/);
  }
});

test('catalogue requests have bounded body size and total time', async t => {
  const large = await serve(t, (_, response) => response.end(' '.repeat(1000)));
  await assert.rejects(fetchAgentCatalogue(large, undefined, { maxBytes: 32 }), /too large/);
  const hung = await serve(t, () => {});
  await assert.rejects(fetchAgentCatalogue(hung, undefined, { timeoutMs: 30 }), /timed out/);
});

test('credentials require HTTPS for remote endpoints and cannot contain line breaks', async () => {
  await assert.rejects(fetchAgentCatalogue('http://example.test/api', 'Bearer synthetic-token'), /requires HTTPS/);
  await assert.rejects(fetchAgentCatalogue('http://127.0.0.1/api', 'Bearer bad\r\nheader'), /Invalid catalogue credential/);
});
