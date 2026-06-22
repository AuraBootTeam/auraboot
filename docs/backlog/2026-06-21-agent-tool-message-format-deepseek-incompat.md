---
created: 2026-06-21
type: backlog
status: active
area: framework/agent, llm-providers
---

# Agent multi-tool flows fail on DeepSeek (OpenAI-compatible) — tool results sent as role:user

## Symptom

The CS agent (and any agent doing more than one tool call) fails on DeepSeek with HTTP 400:

> An assistant message with 'tool_calls' must be followed by tool messages responding to each
> 'tool_call_id'. (insufficient tool messages following tool_calls message)

## Root cause

`StepLoopService` (around lines 244 and 457) appends tool results as a single `role: "user"`
message — the Anthropic `tool_result` content-block style:

```java
messages.add(LlmChatRequest.Message.builder().role("user").content(toolResults).build());
```

OpenAI-compatible providers (DeepSeek) require, after an assistant message carrying `tool_calls`,
a separate `role: "tool"` message per call with a matching `tool_call_id`.
`OpenAiCompatibleLlmProvider` already knows that per-tool shape (`role: "tool"` + `tool_call_id`,
around line 341), but the StepLoop's single `role: "user"` lumping is never converted to it, so
DeepSeek rejects the request.

The agent was built and tested against Anthropic (`claude-sonnet-4-6`); on DeepSeek — the only LLM
key configured in this environment — multi-tool turns 400.

## Impact

- The seeded production `cs_agent` uses `deepseek-chat` (after #1009), so its multi-tool flow
  (create complaint → send reply → log activity) cannot complete on DeepSeek.
- Blocks gold verification of the agent autonomously calling `cmd:crm:create_activity` end-to-end.
- Affects any agent that issues more than one tool call on an OpenAI-compatible provider.

## Fix direction

Make the tool-result message format provider-aware in `StepLoopService` (or push the conversion
entirely into `OpenAiCompatibleLlmProvider`): for OpenAI-compatible providers emit one
`role: "tool"` message per tool call with its `tool_call_id`, instead of a single `role: "user"`
block; keep the Anthropic `role: "user"` `tool_result` format for Anthropic.

## Verification when fixed

Host-first isolated stack + crm imported + seeded `cs_agent` (deepseek-chat) → dispatch an
inbound-email agent task → assert: complaint created, `ab_notification_send_log` status `sent`, and
an `mt_crm_activity_common` "Agent Reply" row. (Recipe: this session's cs-gold stack on slot 71.)

## Also surfaced (lower priority, same flow)

- The agent tool-selector (`d1`) selects only about 9 of ~12 tools per run, so it can drop
  `cmd:crm:create_activity` from the available set even when the prompt asks for it.
- Plan generation on `deepseek-v4-pro` is flaky ("provider did not return a valid JSON plan").
