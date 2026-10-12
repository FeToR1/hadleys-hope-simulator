const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const textmate = require('vscode-textmate');
const oniguruma = require('vscode-oniguruma');

async function grammar() {
  await oniguruma.loadWASM(fs.readFileSync(require.resolve('vscode-oniguruma/release/onig.wasm')));
  const registry = new textmate.Registry({
    onigLib: Promise.resolve({ createOnigScanner: patterns => new oniguruma.OnigScanner(patterns), createOnigString: s => new oniguruma.OnigString(s) }),
    loadGrammar: async () => JSON.parse(fs.readFileSync(path.join(__dirname, '../syntaxes/colony.tmLanguage.json'), 'utf8')),
  });
  return registry.loadGrammar('source.colony');
}

test('tokenizes Colony literals and declarations without treating comments or strings as code', async () => {
  const g = await grammar();
  const scope = line => g.tokenizeLine(line).tokens.map(t => [line.slice(t.startIndex, t.endIndex), t.scopes.at(-1)]);
  for (const literal of ['1s', '0.4per_s', '2kW', '35hp', '19degC', '1e-3', '.5', '12µm']) {
    assert.ok(scope(`let value = ${literal};`).some(([text, s]) => text === literal && s === 'constant.numeric.colony'), literal);
  }
  assert.ok(scope('behavior HeaterControl for Heater {').some(([text, s]) => text === 'behavior' && s === 'keyword.control.colony'));
  assert.ok(scope('param x: Option<Ref<House>>;').some(([text, s]) => text === 'Option' && s === 'support.type.colony'));
  assert.ok(scope('// behavior every 1s').every(([, s]) => s.startsWith('comment.')));
  assert.ok(scope('"behavior 1s // text"').every(([, s]) => s.startsWith('string.')));
  assert.ok(scope('"escaped \\" quote"').some(([, s]) => s === 'constant.character.escape.colony'));
  const block = g.tokenizeLine('/* block');
  assert.ok(g.tokenizeLine('every 1s */ behavior', block.ruleStack).tokens.some(t => t.scopes.at(-1) === 'keyword.control.colony'));
});

test('tokenizes the maintained settlement program with valid state across lines', async () => {
  const g = await grammar();
  const source = fs.readFileSync(path.join(__dirname, '../../../examples/physics/settlement.colony'), 'utf8');
  let stack;
  let keywords = 0;
  for (const line of source.split(/\r?\n/)) {
    const result = g.tokenizeLine(line, stack);
    stack = result.ruleStack;
    keywords += result.tokens.filter(t => t.scopes.at(-1) === 'keyword.control.colony').length;
  }
  assert.ok(keywords > 100);
  assert.equal(stack.depth, 1);
});
