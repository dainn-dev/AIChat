import 'reflect-metadata';
import { Logger, Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { NestFactory } from '@nestjs/core';
import configuration from '../config/configuration';
import { validateEnv } from '../config/env.validation';
import { DatabaseModule } from '../database/database.module';
import { ConversationErasureModule } from '../conversations/conversation-erasure.module';
import { LegacyOptOutPurgeService } from '../conversations/legacy-optout-purge.service';

/**
 * Minimal context for the one-off backfill: config + DB + erasure only, so it
 * boots without Redis, the LLM provider or the HTTP server.
 */
@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
      cache: true,
      load: [configuration],
      validate: validateEnv,
    }),
    DatabaseModule,
    ConversationErasureModule,
  ],
  providers: [LegacyOptOutPurgeService],
})
class LegacyOptOutPurgeScriptModule {}

/**
 * Deploy step, run ONCE after the opt-out-erasure release (see
 * docs/memory-privacy.md §1.1): `npm run purge:legacy-optout` (or
 * `node dist/scripts/purge-legacy-optout.js` in a built image). Exits 1 if
 * any user failed; it is idempotent, so just re-run it.
 */
async function main(): Promise<void> {
  const app = await NestFactory.createApplicationContext(
    LegacyOptOutPurgeScriptModule,
    { logger: ['log', 'warn', 'error'] },
  );
  try {
    const summary = await app.get(LegacyOptOutPurgeService).run();
    process.exitCode = summary.failed > 0 ? 1 : 0;
  } finally {
    await app.close();
  }
}

main().catch((err: unknown) => {
  new Logger('purge-legacy-optout').error(
    `Backfill aborted: ${(err as Error)?.message ?? 'unknown error'}`,
  );
  process.exitCode = 1;
});
