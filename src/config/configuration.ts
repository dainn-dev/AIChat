/**
 * Typed application configuration, loaded from validated environment variables.
 * Access via `ConfigService.get('<namespace>')`.
 */
export interface AppConfig {
  nodeEnv: string;
  port: number;
  corsOrigins: string[];
}

export interface DatabaseConfig {
  url?: string;
  host: string;
  port: number;
  user: string;
  password: string;
  name: string;
  ssl: boolean;
}

export interface S3Config {
  region: string;
  bucket: string;
  accessKeyId?: string;
  secretAccessKey?: string;
  endpoint?: string;
  forcePathStyle: boolean;
}

export interface ObservabilityConfig {
  sentryDsn?: string;
  sentryTracesSampleRate: number;
  posthogApiKey?: string;
  posthogHost: string;
}

const toBool = (value: string | undefined, fallback = false): boolean => {
  if (value === undefined) return fallback;
  return ['1', 'true', 'yes', 'on'].includes(value.toLowerCase());
};

export default () => ({
  app: {
    nodeEnv: process.env.NODE_ENV ?? 'development',
    port: parseInt(process.env.PORT ?? '3000', 10),
    corsOrigins: (process.env.CORS_ORIGINS ?? '*')
      .split(',')
      .map((o) => o.trim())
      .filter(Boolean),
  } satisfies AppConfig,
  database: {
    url: process.env.DATABASE_URL,
    host: process.env.DATABASE_HOST ?? 'localhost',
    port: parseInt(process.env.DATABASE_PORT ?? '5432', 10),
    user: process.env.DATABASE_USER ?? 'aichat',
    password: process.env.DATABASE_PASSWORD ?? 'aichat',
    name: process.env.DATABASE_NAME ?? 'aichat',
    ssl: toBool(process.env.DATABASE_SSL),
  } satisfies DatabaseConfig,
  s3: {
    region: process.env.S3_REGION ?? 'us-east-1',
    bucket: process.env.S3_BUCKET ?? 'aichat-screenshots',
    accessKeyId: process.env.S3_ACCESS_KEY_ID,
    secretAccessKey: process.env.S3_SECRET_ACCESS_KEY,
    endpoint: process.env.S3_ENDPOINT,
    forcePathStyle: toBool(process.env.S3_FORCE_PATH_STYLE),
  } satisfies S3Config,
  observability: {
    sentryDsn: process.env.SENTRY_DSN,
    sentryTracesSampleRate: parseFloat(
      process.env.SENTRY_TRACES_SAMPLE_RATE ?? '0.1',
    ),
    posthogApiKey: process.env.POSTHOG_API_KEY,
    posthogHost: process.env.POSTHOG_HOST ?? 'https://us.i.posthog.com',
  } satisfies ObservabilityConfig,
});
