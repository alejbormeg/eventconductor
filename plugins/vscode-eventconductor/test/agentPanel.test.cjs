const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

// Exercise the shared component's migration logic without a browser or layout engine.
const source = path.resolve(__dirname, '../../../modules/workflow-engine/frontend/src/eventconductor-workflow-graph.ts');
const { outputText } = ts.transpileModule(fs.readFileSync(source, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, experimentalDecorators: true },
});
const exportsObject = {};
vm.runInNewContext(outputText, {
  exports: exportsObject,
  require: name => {
    if (name === 'lit') return { LitElement: class {}, css: () => '', html: () => '', svg: () => '', nothing: null };
    if (name === 'lit/decorators.js') return { customElement: () => value => value, property: () => () => {}, state: () => () => {} };
    if (name === './neutralChrome') return {};
    throw new Error(`Unexpected component dependency: ${name}`);
  },
});
const prototype = exportsObject.MateuWorkflowElk.prototype;
const plain = value => JSON.parse(JSON.stringify(value));

for (const ref of ['previous-agent', 'new-agent']) {
  test(`adopting catalogue configuration for ${ref} removes all overrides and preserves task context`, () => {
    const step = {
      id: 'assess', type: 'AGENT', agent: {
        ref: 'previous-agent', taskPrompt: 'Assess this case', input: { caseId: '${caseId}' }, outputVariable: 'assessment',
        mcp: [], rag: ['limited-handbook'], peers: ['limited-peer'], allowSensitiveVariables: true,
      },
    };
    const original = structuredClone(step);
    const changes = [];
    const component = { updateStep: (id, patch) => changes.push([id, plain(patch)]) };
    prototype.useAgentCatalogueConfiguration.call(component, step, ref);
    assert.deepEqual(changes, [['assess', { agent: {
      ref, taskPrompt: 'Assess this case', input: { caseId: '${caseId}' }, outputVariable: 'assessment',
      allowSensitiveVariables: true,
    } }]]);
    assert.deepEqual(step, original, 'migration must not mutate the imported workflow object');
  });
}
