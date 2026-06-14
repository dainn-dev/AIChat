import { DataSourceOptions } from 'typeorm';
import { DatabaseConfig } from '../config/configuration';

/**
 * Builds TypeORM connection options shared by the runtime (NestJS) and the
 * migration CLI, so both connect to the same database in exactly the same way.
 *
 * `synchronize` is always false — schema changes go through migrations only
 * (WS-2 owns the actual schema).
 */
export function buildDataSourceOptions(cfg: DatabaseConfig): DataSourceOptions {
  const base = {
    type: 'postgres' as const,
    entities: [__dirname + '/../**/*.entity{.ts,.js}'],
    // `!(*.spec)` excludes colocated migration specs (e.g.
    // `*-CoreSchema.spec.ts`); under the ts-node CLI the glob would otherwise
    // import them and crash `migration:run` on Jest globals (`describe`).
    migrations: [__dirname + '/migrations/!(*.spec){.ts,.js}'],
    migrationsTableName: 'migrations',
    synchronize: false,
    logging: false,
  };

  if (cfg.url) {
    return {
      ...base,
      url: cfg.url,
      ssl: cfg.ssl ? { rejectUnauthorized: false } : false,
    };
  }

  return {
    ...base,
    host: cfg.host,
    port: cfg.port,
    username: cfg.user,
    password: cfg.password,
    database: cfg.name,
    ssl: cfg.ssl ? { rejectUnauthorized: false } : false,
  };
}
