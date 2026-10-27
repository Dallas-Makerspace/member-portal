# AI assistants (agent chat)

Multi-turn chat where the model calls tools instead of being handed search results. One platform
(`:service:agent`, built on JetBrains Koog and the Bedrock Converse API) hosts any number of bots;
`ask-ai` is the first.

## How a turn works

1. The browser posts a question to `/api/agent/...` (UI proxy, `AgentApiHandler`), which forwards
   to the service's `/agent/...` routes with the member in `X-Username`. The service re-checks
   the bot's feature flag and audience for that member on every call.
2. `ChatService` saves nothing yet: it starts the turn in an app-scoped coroutine (a dropped
   browser connection does not stop it) and returns `202`. At most 5 turns run at once; others
   wait up to a minute and get a `busy` event.
3. `AgentRuntime` loops: stream the model, run the tools it asks for (30s timeout each, results cut
   to the tool's `maxResultChars` and wrapped in `<data>` tags), feed the results back, until it
   answers or hits the bot's tool-call limit (default 8).
4. Events (`delta`, `tool-start`, `tool-end`, `sources`, `visibility`, `error`, `turn-end`) go to
   `/agent/conversations/{id}/stream`. The whole turn is replayable, so a reload mid-answer picks
   up where it was. A stopped or failed turn still saves what was produced.
5. The turn's messages, tool calls and cost are written in one transaction.

Prompt caching: the system prompt, tool definitions and the last message carry cache points, so
follow-up turns pay the cheap cache-read price. Long chats drop old tool output, then old turns,
to stay under about 240k characters (Haiku 5.5 prompts over 100k tokens cost 5x).

## Privacy and retention

- A chat starts public (any member allowed to use the bot can read it) unless the bot sets
  `startsPrivate`.
- It becomes private, permanently, when a tool with `touchesPersonalInfo` runs or when
  `PiiMasker` finds personal details in a question. `private_reason` says why.
- Only the owner can continue, delete or cancel a chat. Chats are deleted two years after their
  last message (nightly job, 03:30 Central).

## Limits

| Setting | Default |
| --- | --- |
| `agent.monthly-spend-cap-usd` | 20 (0 = unlimited) |
| `agent.user-daily-turn-cap` | 30 questions per member per Central day |
| `agent.model.default` | `us.anthropic.claude-haiku-5-5` |
| Bot flag `agent-chat.enabled` | pilot (mandarlimaye + "IT Early Access") |

Spend is summed from `agent_message.cost_usd` (list prices in `ModelPricing`), cached for 10
minutes and topped up by each finished turn.

## Adding a bot or a tool

- A tool implements `AgentTool` (descriptor, `touchesPersonalInfo`, `maxResultChars`, `run`). Keep
  each bot to a handful of tools; the descriptors are part of every prompt.
- A bot is a `BotDefinition` contributed with `@Provides @IntoSet` in the owning module's Dagger
  module (see `AskAiModule.provideAskAiBot`): key, prompt, tools, feature flag (new flags default
  to the pilot), audience, `startsPrivate`.
- Add the bot's tool names to `toolLabel` in `static/js/agent/markdown.js` for friendly chips.

## Ask AI bot

Tools: `search_source` and `read_source_page` (Source, the Confluence wiki), `search_talk`,
`search_calendar` (marks the chat private: organizer usernames), `search_online_courses`
(DMS Learn). `/ask-ai` shows the chat when the flag is on for the member and the old page
otherwise; `/ask-ai/q/{slug}` links keep working from the old answer cache.

## IT assistant and course assistant

- **`it-assistant`** (`:service:itbot`): reads local clones of the public DMS repositories with `git`
  (search, files, history, blame), the app and request logs (`query_logs`), and can refresh the
  clones. Chats are always private. Audience: AD group `agent.it-bot.ad-group`; repos:
  `agent.it-bot.repos`. Clones live in the container's temp dir and sync on start and at 04:00
  Central. Page: `/it-assistant`.
- **`course-assistant`** (`:service:coursebot`): helps members of any "<prefix> Online Course Editors"
  group design course outlines, quizzes and lesson plans, with the wiki and DMS Learn catalog as
  research. Chats are always private. Page: `/course-assistant`.
- **Artifacts** (any bot can opt in by adding the four artifact tools): documents the assistant
  writes beside the chat. Kinds are `markdown`, `course_outline` and `quiz` (JSON, checked on save).
  Every save is a new version; a stale save is refused. The chat owner can edit in the Documents
  panel; quizzes export to Moodle GIFT and Moodle XML (question bank import), the rest to Markdown.
  Tables: `service/docs/sql/agent-artifacts-migration.sql`.
- Each bot's page and header link show only for members the service allows
  (`GET /agent/bots`: flag plus audience).

## Evals

`evals/` (promptfoo, run by hand, not in CI) asks each bot real questions through
`POST /agent/{bot}/eval-turn` and checks tool use, answers, cost and speed. The endpoint exists only
when the service starts with `AGENT_EVAL_ENABLED=true` (an env var, not a config flag, because dev
and prod share a database), saves nothing, and accepts `model` to compare Haiku 5.5, Sonnet 5.5 and
GPT-6.1 Sol. See `evals/README.md` for how to run it and the latest model comparison.

Explicit prompt-cache points are sent only to Anthropic models; Sol rejects them.

## Local testing

`:service:agent` tests use H2 and a scripted model (`MockChatModelClient`, also used when
`USE_MOCK_SERVICES` is on). `KoogBedrockSpikeTest` calls real Bedrock when run with `KOOG_SPIKE=1`.
