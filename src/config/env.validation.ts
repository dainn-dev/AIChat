import 'reflect-metadata';
import { plainToInstance, Type } from 'class-transformer';
import {
  IsEnum,
  IsInt,
  IsOptional,
  IsString,
  Max,
  Min,
  validateSync,
} from 'class-validator';

export enum NodeEnv {
  Development = 'development',
  Test = 'test',
  Production = 'production',
}

/**
 * Schema for the subset of environment variables that must be present and
 * well-formed for the app to boot. Optional integrations (S3 credentials,
 * Sentry DSN, PostHog key) are intentionally not required here so the service
 * can boot in local/dev without external accounts.
 */
class EnvironmentVariables {
  @IsEnum(NodeEnv)
  @IsOptional()
  NODE_ENV: NodeEnv = NodeEnv.Development;

  @Type(() => Number)
  @IsInt()
  @Min(0)
  @Max(65535)
  @IsOptional()
  PORT = 3000;

  @IsString()
  @IsOptional()
  DATABASE_URL?: string;

  @IsString()
  @IsOptional()
  DATABASE_HOST?: string;

  @Type(() => Number)
  @IsInt()
  @Min(0)
  @Max(65535)
  @IsOptional()
  DATABASE_PORT?: number;

  @IsString()
  @IsOptional()
  DATABASE_USER?: string;

  @IsString()
  @IsOptional()
  DATABASE_PASSWORD?: string;

  @IsString()
  @IsOptional()
  DATABASE_NAME?: string;

  // ── Auth ────────────────────────────────────────────────────────────
  // Optional in dev/test (a default secret is used so the app still boots);
  // set a strong, unique JWT_ACCESS_SECRET in production.
  @IsString()
  @IsOptional()
  JWT_ACCESS_SECRET?: string;

  @IsString()
  @IsOptional()
  JWT_ACCESS_TTL?: string;

  @Type(() => Number)
  @IsInt()
  @Min(1)
  @IsOptional()
  REFRESH_TOKEN_TTL_DAYS?: number;

  @Type(() => Number)
  @IsInt()
  @Min(4)
  @Max(15)
  @IsOptional()
  BCRYPT_ROUNDS?: number;
}

export function validateEnv(config: Record<string, unknown>) {
  const validated = plainToInstance(EnvironmentVariables, config, {
    enableImplicitConversion: true,
  });
  const errors = validateSync(validated, {
    skipMissingProperties: false,
  });

  if (errors.length > 0) {
    throw new Error(
      `Invalid environment configuration:\n${errors
        .map(
          (e) =>
            `  - ${e.property}: ${Object.values(e.constraints ?? {}).join(', ')}`,
        )
        .join('\n')}`,
    );
  }
  return validated;
}
