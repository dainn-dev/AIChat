import 'reflect-metadata';
import { Logger } from '@nestjs/common';
import { readFile } from 'fs/promises';
import { basename } from 'path';
import configuration from '../config/configuration';
import { parseArgs } from './script-args';

/**
 * Uploads a dataset from `training:export` and starts an OpenAI fine-tuning
 * job, or prints a job's status (docs/model-training.md):
 *   npm run training:finetune -- --file training.jsonl [--base gpt-4o-mini-2024-07-18] [--suffix dboard]
 *   npm run training:finetune -- --status ftjob-...
 * When the job succeeds, set LLM_REPLY_MODEL to its `fine_tuned_model`.
 * Uses LLM_API_KEY / LLM_BASE_URL, like the API itself.
 */
const DEFAULT_BASE_MODEL = 'gpt-4o-mini-2024-07-18';
const DEFAULT_SUFFIX = 'dboard-reply';

async function main(): Promise<void> {
  const args = parseArgs(process.argv.slice(2));
  const llm = configuration().llm;
  if (!llm.apiKey) throw new Error('LLM_API_KEY is not set');
  const baseUrl = (llm.baseUrl?.trim() || 'https://api.openai.com/v1').replace(
    /\/+$/,
    '',
  );
  const auth = { Authorization: `Bearer ${llm.apiKey}` };
  const log = new Logger('fine-tune');

  // Only the fields this script prints or forwards.
  interface ApiObject {
    id: string;
    status?: string;
    fine_tuned_model?: string | null;
    trained_tokens?: number | null;
    error?: { message?: string } | null;
  }

  async function call(path: string, init: RequestInit): Promise<ApiObject> {
    const res = await fetch(`${baseUrl}${path}`, init);
    const body = await res.text();
    if (!res.ok) throw new Error(`${path} -> HTTP ${res.status}: ${body}`);
    return JSON.parse(body) as ApiObject;
  }

  if (args.status) {
    const job = await call(`/fine_tuning/jobs/${args.status}`, {
      headers: auth,
    });
    log.log(
      `job=${job.id} status=${job.status} fine_tuned_model=${job.fine_tuned_model ?? '-'} ` +
        `trained_tokens=${job.trained_tokens ?? '-'} error=${job.error?.message ?? '-'}`,
    );
    return;
  }

  const file = args.file;
  if (!file)
    throw new Error('Missing --file <training.jsonl> (or --status <job id>)');
  const form = new FormData();
  form.append('purpose', 'fine-tune');
  form.append('file', new Blob([await readFile(file)]), basename(file));
  const uploaded = await call('/files', {
    method: 'POST',
    headers: auth,
    body: form,
  });
  log.log(`Uploaded ${basename(file)} as ${uploaded.id}`);

  const job = await call('/fine_tuning/jobs', {
    method: 'POST',
    headers: { ...auth, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      training_file: uploaded.id,
      model: args.base ?? DEFAULT_BASE_MODEL,
      suffix: args.suffix ?? DEFAULT_SUFFIX,
    }),
  });
  log.log(
    `Started fine-tuning job ${job.id} (status ${job.status}). Check it with ` +
      `npm run training:finetune -- --status ${job.id}`,
  );
}

main().catch((err: unknown) => {
  new Logger('fine-tune').error((err as Error)?.message ?? 'unknown error');
  process.exitCode = 1;
});
