# AI assistant evals

Manual checks that an assistant still behaves after a prompt, tool or model change. They run real
questions through the real tools and a real model, so they cost money (about 3 cents for Haiku 5.5
per full run, 30 cents for Sol, 80 cents for Sonnet) and are not part of CI.

[promptfoo](https://www.promptfoo.dev) drives them. Each case asks the assistant one or more
questions through `POST /agent/{bot}/eval-turn`, then checks:

- **Tools**: the right ones were called (`tools`), the wrong ones weren't (`avoidTools`, `noTools`),
  and the turn stayed within a call budget (`maxCalls`). Tool errors and "gave up" fail a case.
- **Answer**: must/must-not mention strings, a source link (`cites`), no emails or phone numbers
  (`noPii`), and a plain-language `rubric` graded by Haiku 5.5.
- **Cost and speed**: under `maxCostUsd` and 90 seconds.

## Run

1. Start the service locally against real backends (`service/.env` filled in, `DEV_MODE=false`,
   `USE_MOCK_SERVICES=false`) with `AGENT_EVAL_ENABLED=true`. The endpoint does not exist without
   it, and never set it in production. It saves nothing; chats live in memory for the call.
2. AWS credentials for Bedrock must be available (default profile or env vars).
3. From this folder:

```bash
npm install
export AWS_REGION=us-east-1
npx promptfoo eval -c promptfooconfig.yaml --filter-providers haiku --no-cache -j 3 -o output/run.json
node summary.js output/run.json --failures      # per-case table, only failures
npx promptfoo view                              # browser UI
```

Drop `--filter-providers` to compare Haiku 5.5, Sonnet 5.5 and GPT-6.1 Sol on the same cases.
Add `--repeat 3` to see how stable a result is; one run of 48 cases moves by 1-3 cases from noise
alone. Keep `-j` at 3 or below (the service allows five concurrent turns).

The service URL and API key come from `EVAL_SERVICE_URL` (default `http://127.0.0.1:8091`),
`EVAL_API_KEY` and `EVAL_API_CLIENT`; if unset the first API client in `service/.env` is used.

## Cases

`ask-ai/cases.js`, expanded by `tests.js`. Use generic questions and fake names only; checks
should be about behaviour (searches, cites, doesn't invent, declines) rather than exact wiki
wording, which changes. Add a case whenever a real conversation goes wrong.

## Model comparison (2026-10-26, Ask AI, 48 cases)

| Model | Passed (3 runs) | Cost per run | Median time |
| --- | --- | --- | --- |
| Haiku 5.5 | 138 / 144 | $0.04 | 6.9 s |
| GPT-6.1 Sol | 138 / 144 | $0.24 | 6.3 s |
| Sonnet 5.5 (1 run) | 42 / 48 | $0.82 | 7.9 s |

Haiku and Sol tie. Haiku's misses are flaky (tool-call budget, one rubric); Sol's are steady (it
calls the calendar with an empty query, and states an uncited fact on opening hours). No case is
one a stronger model passes and Haiku consistently fails, so Haiku stays the default and an
escalation tool is not worth building yet. Re-run when the prompt, tools or models change.
