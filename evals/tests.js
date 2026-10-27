// Expands the compact cases in ./<bot>/cases.js into promptfoo tests.
//   tools:      tool names that must all be called        noTools: true -> no tool may be called
//   avoidTools: tool names that must not be called         maxCalls: most tool calls allowed
//   mention:    every string must appear (case-insensitive) mentionAny: at least one must appear
//   noPii:      no email address or phone number in the answer
//   makes:      kinds of document that must exist at the end (markdown, course_outline, quiz)
//   questions:  the quiz must have at least this many questions
//   noDocuments: no document may be created
//   revised:    a document must have been saved at least twice (version >= 2)
//   allowToolErrors: a refused tool call is fine (safety cases)
//   forbid:     none may appear                            cites: answer must link to a source
//   rubric:     plain-language check for the judge model
const DEFAULTS = {maxCostUsd: 0.1, maxLatencyMs: 90000};

function js(expr) {
  return {type: 'javascript', value: expr};
}

function expand(c) {
  const meta = `context.providerResponse.metadata`;
  const asserts = [
    js(`!${meta}.gaveUp`),
    ...(c.allowToolErrors ? [] : [js(`${meta}.toolErrors === 0`)]),
    {type: 'cost', threshold: c.maxCostUsd ?? DEFAULTS.maxCostUsd},
    {type: 'latency', threshold: DEFAULTS.maxLatencyMs},
  ];
  for (const t of c.tools || []) asserts.push(js(`${meta}.toolNames.includes(${JSON.stringify(t)})`));
  for (const t of c.avoidTools || []) asserts.push(js(`!${meta}.toolNames.includes(${JSON.stringify(t)})`));
  if (c.noTools) asserts.push(js(`${meta}.toolNames.length === 0`));
  if (c.maxCalls !== undefined) asserts.push(js(`${meta}.toolNames.length <= ${c.maxCalls}`));
  for (const k of c.makes || []) asserts.push(js(`${meta}.artifactKinds.includes(${JSON.stringify(k)})`));
  if (c.noDocuments) asserts.push(js(`${meta}.artifacts.length === 0`));
  if (c.revised) asserts.push(js(`${meta}.artifacts.some(a => a.version >= 2)`));
  if (c.questions) {
    asserts.push(js(`${meta}.artifacts.some(a => a.kind === 'quiz' && JSON.parse(a.content).questions.length >= ${c.questions})`));
  }
  if (c.cites) asserts.push(js(`output.includes('](http') || output.includes('](/')`));
  for (const s of c.mention || []) {
    asserts.push({type: 'icontains', value: s});
  }
  if (c.mentionAny) asserts.push({type: 'icontains-any', value: c.mentionAny});
  if (c.noPii) asserts.push(js(`!/[\w.+-]+@[\w-]+\.[\w.]+|\d{3}[-. )]+\d{3,4}[-. ]\d{4}/.test(output)`));
  for (const s of c.forbid || []) asserts.push({type: 'not-icontains', value: s});
  if (c.rubric) {
    asserts.push({
      type: 'llm-rubric',
      value: `A makerspace member asked an assistant: "{{question}}"\nCheck the assistant's final answer against this: ${c.rubric}`,
    });
  }
  return {
    description: c.id,
    vars: {question: c.turns ? c.turns[c.turns.length - 1] : c.question, ...(c.turns ? {turns: JSON.stringify(c.turns)} : {})},
    metadata: {category: c.category},
    assert: asserts,
  };
}

module.exports = function generate(config) {
  const cases = require(`./${config?.bot || 'ask-ai'}/cases.js`);
  return cases.map(expand);
};
