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

/**
 * Per-tier usage limits. `null` for a dimension means "unlimited".
 *
 * `dailySpendCapMicroUsd` is the per-user, per-day LLM spend ceiling in
 * micro-USD (1e-6 USD). It bounds abuse on the Pro "unlimited" plan
 * (DAI-124 §5.10) even when reply/screenshot counts are uncapped.
 */
export interface TierLimits {
  repliesPerDay: number | null;
  screenshotsPerDay: number | null;
  dailySpendCapMicroUsd: number | null;
}

export interface UsageConfig {
  free: TierLimits;
  pro: TierLimits;
}

/**
 * LLM provider configuration. The concrete provider is gated on epic Decision #1
 * (provider + keys); until that lands, `provider` defaults to the deterministic,
 * keyless `stub` so the pipeline is fully exercisable. Swapping in a real
 * provider (e.g. Claude / GPT / Gemini) is a config + binding change only —
 * see `src/ai/provider`.
 */
export interface LlmConfig {
  provider: string;
  replyModel?: string;
  analysisModel?: string;
  apiKey?: string;
  baseUrl?: string;
  requestTimeoutMs: number;
  maxRepairRetries: number;
}

export interface AuthConfig {
  /** Secret used to sign/verify short-lived access JWTs. */
  jwtAccessSecret: string;
  /** Access-token lifetime, expressed as a `jsonwebtoken` duration string. */
  accessTokenTtl: string;
  /** Opaque refresh-token lifetime, in days. */
  refreshTokenTtlDays: number;
  /** bcrypt cost factor for password hashing. */
  bcryptRounds: number;
}

const toBool = (value: string | undefined, fallback = false): boolean => {
  if (value === undefined) return fallback;
  return ['1', 'true', 'yes', 'on'].includes(value.toLowerCase());
};

/** Parse a non-negative integer env var, falling back when unset/blank. */
const toInt = (value: string | undefined, fallback: number): number => {
  if (value === undefined || value.trim() === '') return fallback;
  const n = parseInt(value, 10);
  return Number.isFinite(n) ? n : fallback;
};

/**
 * Parse an optional USD spend cap (e.g. "20" or "20.50") into micro-USD.
 * An unset/blank/`unlimited`/`0` value disables the cap (returns `null`).
 */
const toSpendCapMicroUsd = (value: string | undefined): number | null => {
  if (value === undefined) return null;
  const trimmed = value.trim().toLowerCase();
  if (trimmed === '' || trimmed === 'unlimited' || trimmed === 'none') {
    return null;
  }
  const usd = parseFloat(trimmed);
  if (!Number.isFinite(usd) || usd <= 0) return null;
  return Math.round(usd * 1_000_000);
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
  usage: {
    free: {
      repliesPerDay: toInt(process.env.USAGE_FREE_REPLIES_PER_DAY, 20),
      screenshotsPerDay: toInt(process.env.USAGE_FREE_SCREENSHOTS_PER_DAY, 5),
      // Free tier is bounded by counts; no separate spend cap unless set.
      dailySpendCapMicroUsd: toSpendCapMicroUsd(
        process.env.USAGE_FREE_DAILY_SPEND_CAP_USD,
      ),
    },
    pro: {
      repliesPerDay: null,
      screenshotsPerDay: null,
      // Default Pro abuse ceiling: $20/user/day. Set to "unlimited" to disable.
      dailySpendCapMicroUsd: toSpendCapMicroUsd(
        process.env.USAGE_PRO_DAILY_SPEND_CAP_USD ?? '20',
      ),
    },
  } satisfies UsageConfig,
  llm: {
    provider: process.env.LLM_PROVIDER ?? 'stub',
    replyModel: process.env.LLM_REPLY_MODEL,
    analysisModel: process.env.LLM_ANALYSIS_MODEL,
    apiKey: process.env.LLM_API_KEY,
    baseUrl: process.env.LLM_BASE_URL,
    requestTimeoutMs: parseInt(
      process.env.LLM_REQUEST_TIMEOUT_MS ?? '30000',
      10,
    ),
    maxRepairRetries: parseInt(process.env.LLM_MAX_REPAIR_RETRIES ?? '1', 10),
  } satisfies LlmConfig,
  auth: {
    // A dev/test default keeps the service bootable without secrets, matching
    // the scaffold's local-first philosophy. MUST be overridden in production.
    jwtAccessSecret:
      process.env.JWT_ACCESS_SECRET ?? 'dev-access-secret-change-me',
    accessTokenTtl: process.env.JWT_ACCESS_TTL ?? '15m',
    refreshTokenTtlDays: parseInt(
      process.env.REFRESH_TOKEN_TTL_DAYS ?? '30',
      10,
    ),
    bcryptRounds: parseInt(process.env.BCRYPT_ROUNDS ?? '10', 10),
  } satisfies AuthConfig,
});
