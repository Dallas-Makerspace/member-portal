// promptfoo provider: asks a bot (one or more messages in a throwaway chat) through the service's
// POST /agent/{bot}/eval-turn. The service must run with AGENT_EVAL_ENABLED=true.
const fs = require('fs');
const path = require('path');

function localEnv(name) {
  if (process.env[name]) return process.env[name];
  try {
    const file = fs.readFileSync(path.join(__dirname, '..', '..', 'service', '.env'), 'utf8');
    const match = file.match(new RegExp(`^${name}=(.*)$`, 'm'));
    return match ? match[1].trim().replace(/^"|"$/g, '') : undefined;
  } catch (e) {
    return undefined;
  }
}

class DmsAgentProvider {
  constructor(options = {}) {
    this.config = options.config || {};
    this.label = options.label || this.config.model || 'default';
  }

  id() {
    return `dms-agent:${this.label}`;
  }

  async callApi(prompt, context) {
    const baseUrl = process.env.EVAL_SERVICE_URL || 'http://127.0.0.1:8091';
    const key = process.env.EVAL_API_KEY || localEnv('API_CLIENT_0_KEY');
    const client = process.env.EVAL_API_CLIENT || localEnv('API_CLIENT_0_NAME');
    if (!key || !client) return {error: 'Set EVAL_API_KEY and EVAL_API_CLIENT (or fill service/.env)'};

    // promptfoo expands array vars into separate tests, so turns arrive as a JSON string.
    const messages = context.vars.turns ? JSON.parse(context.vars.turns) : [prompt];
    const body = {messages};
    if (this.config.model) body.model = this.config.model;
    const started = Date.now();
    let res;
    try {
      res = await fetch(`${baseUrl}/agent/${this.config.bot || 'ask-ai'}/eval-turn`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Api-Key': key,
          'X-Api-Client': client,
          'X-Username': 'eval-bot',
        },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(240000),
      });
    } catch (e) {
      return {error: `Request failed: ${e.message}`};
    }
    const json = await res.json().catch(() => ({}));
    if (!res.ok || !json.data) return {error: `HTTP ${res.status}: ${json.message || 'no body'}`};

    const turns = json.data.turns;
    const last = turns[turns.length - 1];
    const calls = turns.flatMap(t => t.toolCalls);
    return {
      output: last.answer,
      cost: json.data.totalCostUsd,
      latencyMs: Date.now() - started,
      tokenUsage: {
        prompt: turns.reduce((n, t) => n + t.inputTokens + t.cacheReadTokens, 0),
        completion: turns.reduce((n, t) => n + t.outputTokens, 0),
        total: turns.reduce((n, t) => n + t.inputTokens + t.cacheReadTokens + t.outputTokens, 0),
      },
      metadata: {
        model: json.data.model,
        toolNames: calls.map(c => c.name),
        toolCalls: calls.map(c => `${c.name} ${c.args}`),
        toolErrors: calls.filter(c => !c.ok).length,
        sourceUrls: turns.flatMap(t => t.sources.map(s => s.url)),
        lastTurnSources: last.sources.length,
        gaveUp: turns.some(t => t.gaveUp),
        answers: turns.map(t => t.answer),
      },
    };
  }
}

module.exports = DmsAgentProvider;
