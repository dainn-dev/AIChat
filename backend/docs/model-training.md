# Model training on user conversations

> **Status: IMPLEMENTED (engineering) — needs legal/compliance review before the
> first real training run.** The dataset includes the messages of the people
> users chat with ("them"), who never consented themselves. Check this against
> the applicable personal-data law (in Vietnam, the Personal Data Protection
> Law) and update the privacy policy before running `training:finetune` on
> production data.

DBoard can fine-tune its reply model on the conversations of users who
explicitly agree to it. Nothing runs automatically: an operator exports the
dataset, starts an OpenAI fine-tuning job, and switches the reply model.

## 1. Consent (`users.training_opt_in`)

- Separate from `memory_opt_in`, **off by default** (migration
  `UserTrainingOptIn1719100000000`). The Android account screen shows it as
  "Đóng góp dữ liệu để cải thiện AI" and asks for confirmation before turning
  it on; the dialog states that contacts' messages are included, that data goes
  to OpenAI, and that a trained model cannot unlearn.
- `PATCH /me/settings { "training_opt_in": true }` sets
  `training_opt_in_at = now()` (kept if already on). Only messages **stored at
  or after** that moment are exported: turning it on never pulls in older
  history. For keyboard captures "stored" means captured by the backend; a
  capture after opt-in can include older messages that were visible on screen.
- `{ "training_opt_in": false }` clears `training_opt_in_at`; the next export
  leaves the user out. It deletes nothing (memory/erasure settings are
  unchanged), and **models already trained keep what they learned** — the
  only remedy is to retrain without that data.
- It does not make anything be stored: only conversations that exist anyway
  are used (keyboard captures need `memory_opt_in`; screenshot/share
  conversations are always stored).

## 2. Dataset (`npm run training:export`)

```bash
npm run training:export -- --out training.jsonl [--max-per-user 2000]
# in a built image:
node dist/scripts/export-training-data.js --out /tmp/training.jsonl
```

`TrainingExportService` + the pure `buildExamples` (`src/training/`):

- One example per reply the user really sent: a run of "me" messages (up to 3
  bubbles, ≤ 500 chars) right after a "them" message; the 20 messages before it
  are the conversation.
- The prompt is `ContextBuilder.buildReplyPrompt` (tone `Friendly`, `count: 1`,
  no memories, no sticker tags) and the answer is `{"replies":[{"text":…}]}`,
  i.e. the exact format the pipeline uses at inference time.
- Every message goes through `SensitiveDataFilter`: phone/email/card/address
  are masked; a window containing a drop-category message (health, finances,
  minors) is skipped entirely. The heuristics are English-leaning — Vietnamese
  sensitive content is only partly caught.
- The file contains **no** user id, conversation id, platform or contact label.
  Contact names typed inside messages are not removed.
- At most `--max-per-user` examples per user; consent is re-checked per
  conversation while the export runs. Logs counts only.

The `.jsonl` holds private message text: create it on the Pi, never commit it
or copy it to shared storage, and delete it once the job has read it.

## 3. Fine-tuning (`npm run training:finetune`)

```bash
npm run training:finetune -- --file training.jsonl [--base gpt-4o-mini-2024-07-18] [--suffix dboard-reply]
npm run training:finetune -- --status ftjob-...
```

Uploads the file (`purpose: fine-tune`) and starts an OpenAI fine-tuning job
with `LLM_API_KEY` / `LLM_BASE_URL`. When the job succeeds, set
`LLM_REPLY_MODEL=<fine_tuned_model>` and restart the API. Extraction and
analysis keep `LLM_ANALYSIS_MODEL`. Roll back by unsetting `LLM_REPLY_MODEL`.

OpenAI retains uploaded training files until they are deleted; delete the file
in the OpenAI dashboard (or `DELETE /v1/files/{id}`) after the job finishes.

## 4. Before the first production run

- [ ] Legal review of training on third parties' messages; privacy policy updated.
- [ ] Compare the fine-tuned model with the base model on held-out chats
      before switching `LLM_REPLY_MODEL`.
- [ ] Decide how often to retrain, so opt-outs and erasures reach the model.
