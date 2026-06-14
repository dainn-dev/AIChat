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
    // Exclude colocated `*.spec.ts` tests — the CLI would otherwise `require`
    // them and crash on Jest globals (`describe is not defined`).
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
