import { Module } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { TypeOrmModule } from '@nestjs/typeorm';
import { DatabaseConfig } from '../config/configuration';
import { buildDataSourceOptions } from './data-source-options';

/**
 * Wires the PostgreSQL (+ pgvector) connection into Nest. Connection options
 * are derived from validated config and shared with the migration CLI via
 * `buildDataSourceOptions`, so runtime and migrations never drift.
 */
@Module({
  imports: [
    TypeOrmModule.forRootAsync({
      inject: [ConfigService],
      useFactory: (config: ConfigService) => ({
        ...buildDataSourceOptions(
          config.getOrThrow<DatabaseConfig>('database'),
        ),
        autoLoadEntities: true,
      }),
    }),
  ],
})
export class DatabaseModule {}
