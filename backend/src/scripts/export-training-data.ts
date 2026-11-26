import 'reflect-metadata';
import { Logger, Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { NestFactory } from '@nestjs/core';
import configuration from '../config/configuration';
import { validateEnv } from '../config/env.validation';
import { DatabaseModule } from '../database/database.module';
import { TrainingExportService } from '../training/training-export.service';
import { parseArgs } from './script-args';

/** Config + DB only: boots without Redis, the LLM provider or HTTP. */
@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
      cache: true,
      load: [configuration],
      validate: validateEnv,
    }),
    DatabaseModule,
  ],
  providers: [TrainingExportService],
})
class TrainingExportScriptModule {}

/**
 * Writes the fine-tuning dataset of opted-in users (docs/model-training.md):
 *   npm run training:export -- --out training.jsonl [--max-per-user 2000]
 * The file contains private message text: keep it off shared storage and
 * delete it once the fine-tuning job has read it.
 */
async function main(): Promise<void> {
  const args = parseArgs(process.argv.slice(2));
  const outPath = args.out;
  if (!outPath) throw new Error('Missing --out <file.jsonl>');
  const maxPerUser = args['max-per-user']
    ? Number(args['max-per-user'])
    : undefined;
  if (maxPerUser !== undefined && !(maxPerUser > 0)) {
    throw new Error('--max-per-user must be a positive number');
  }

  const app = await NestFactory.createApplicationContext(
    TrainingExportScriptModule,
    { logger: ['log', 'warn', 'error'] },
  );
  try {
    await app.get(TrainingExportService).run({ outPath, maxPerUser });
  } finally {
    await app.close();
  }
}

main().catch((err: unknown) => {
  new Logger('export-training-data').error(
    `Export aborted: ${(err as Error)?.message ?? 'unknown error'}`,
  );
  process.exitCode = 1;
});
