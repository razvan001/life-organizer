# Life Organizer — Brain-Dump Extraction for Setup — Design Spec

- **Date:** 2026-09-26 (written down 2026-09-27, after implementation; the original save failed)
- **Status:** Implemented in `7fbf09c` (feature) and `1ac5464` (logging). Not yet tested against a live Groq call.
- **Builds on:** Phase 2 natural-language commands (`2026-09-22-life-organizer-phase2-nl-commands-design.md`), migrated from Anthropic to Groq in `4264a26`.

## 1. Context and scope

The Setup page could fill its goals/constraints preview in two ways: regex parsing of two structured text boxes (`GoalTreeParser`/`ConstraintParser`), or loading the saved setup. The original design also wanted a third way: paste a free-form brain dump and let an LLM extract the structure. Phase 2 deferred this to keep its scope small.

Decided with the user: **goals and constraints only**, matching `SetupParseResponse` exactly. No new "Task" domain type (that needs engine work and is a separate feature).

## 2. Architecture

```
SetupController        POST /api/setup/extract
  SetupExtractionService   sanitizes and validates the model's reply
    SetupExtractionClient    interface (ai/client)
      GroqExtractionClient     own strict JSON schema
        GroqChatClient           shared Groq chat-completions call (also used by GroqCommandClient)
```

- `GroqChatClient` (package-private) holds the request/POST/parse logic that used to live inside `GroqCommandClient`: `<T> T call(systemPrompt, userMessage, responseFormat, Class<T>)` plus `jsonSchemaResponseFormat(name, schemaJson)`. It throws `MalformedLlmReplyException` for no choices, empty content or unparsable JSON, and lets `RestClientException` propagate.
- `GroqCommandClient` keeps its public constructor `(RestClient, String)` and now delegates to `GroqChatClient`.
- `LlmClientConfig` builds a second bean, `SetupExtractionClient`, under the same `GROQ_API_KEY` condition and `app.ai.model` default (`openai/gpt-oss-120b`). `aiEnabled` in `/api/config` is unchanged.

## 3. Model reply shape

`SetupExtractionResult(categories, goals, constraints, warnings)` with `ExtractedGoal` using boxed `Integer` for `priority` and `defaultBlockMinutes`. `GoalDto` uses primitive `int`, which Jackson cannot fill from a JSON `null`, so the raw reply needs its own type.

The strict JSON schema lists every property under `required`; nullable values use `["type","null"]`. Only constraint `type` has an `enum` (the four engine types). `energy`, `preferredTime` and `days` have none, because the service defaults or drops invalid values anyway.

## 4. Service rules (`SetupExtractionService`)

- No client bean → `AiNotConfiguredException` (503).
- Context sent to the model: the existing categories (id + name), or "No categories exist yet", followed by the text.
- `MalformedLlmReplyException` → a normal response with empty lists and the warning "Could not extract anything from that text. Try rephrasing or use the text boxes instead."
- `RestClientException` propagates; the controller maps it to `AssistantUnavailableException` (502).
- Sanitizing never throws; it defaults or drops with a warning:
  - priority null or outside 1–5 → 3; defaultBlockMinutes null or ≤ 0 → 60; invalid energy → MEDIUM; invalid preferredTime → ANY.
  - Every category, goal and constraint is also checked with `SetupDtoMapper.toDomain` (lengths ≤ 255, deadline format, constraint required fields and day names). A failure drops that item with a warning, so the user never previews something that would fail on Save.
  - A category id that already exists keeps the existing name, never the model's. A goal that uses an existing category the model did not repeat gets that category added to the response.
  - A goal whose category is unknown is dropped. A `FREQUENCY_QUOTA` whose goal is unknown, or a `DURATION_QUOTA`/`EVENING_QUOTA` whose category is unknown, is dropped.
  - The model's own warnings are kept.
- Logging: INFO for request size and final counts, DEBUG for the model's raw counts and adjusted values, WARN for every dropped item.

## 5. API

`POST /api/setup/extract` with `{text}`. Blank text → 400 "text is required". More than 4000 characters → 400. The response is `SetupParseResponse`.

## 6. Frontend

`SetupPage` fetches `/api/config`. When `aiEnabled`, it shows a textarea ("Or describe your goals and constraints in your own words", max 4000) and an "Extract goals and constraints" button. A result becomes the preview titled "Extracted from your text (not saved yet)". Nothing is saved until Save. Without a key the box is hidden.

## 7. Tests

- `GroqExtractionClientTest` (MockRestServiceServer): request shape (model, strict json_schema, messages), nullable numbers parse, no choices and non-JSON content are malformed, non-2xx throws `RestClientException`.
- `SetupExtractionServiceTest`: no client, malformed reply, connectivity failure, pass-through, defaults, unknown category, existing-name kept, existing category synthesized, overlong text, bad deadline, constraint validation and references, null lists.
- `SetupControllerTest`: success, blank, too long, 502, 503.
- `SetupPage.test.tsx`: hidden without AI, extract sends the text and shows the preview, error shown.

## 8. Open follow-up

First live Groq call: confirm the schema is accepted in strict mode and that `openai/gpt-oss-120b` extracts sensibly. If Groq rejects the schema, change the schema in `GroqExtractionClient`, not the sanitizing rules.
