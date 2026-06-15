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
 * LLM provider configuration. Per epic Decision #1 the default is the
 * OpenAI-compatible provider: with `apiKey` set and no explicit `LLM_PROVIDER`,
 * `provider` resolves to `openai`; keyless (local/test) it stays the
 * deterministic `stub` so the pipeline is fully exercisable. `baseUrl`
 * (`LLM_BASE_URL`) repoints the OpenAI client at a proxy/gateway. Swapping
 * providers is a config + binding change only — see `src/ai/provider`.
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

/**
 * Embedding service configuration (Phase 2 / DAI-146, MS-1). Reuses the
 * Decision-#1 OpenAI credentials (`LLM_API_KEY` / `LLM_BASE_URL`) so the
 * embeddings endpoint shares the provider key/base with chat completions — set
 * once, used by both. `provider` resolves to `openai` when a key is present,
 * else the keyless deterministic `stub` (local/test), mirroring `LlmConfig`.
 *
 * `model` + `dimensions` are the locked working default: OpenAI
 * `text-embedding-3-small` at N=1536 (cosine distance). `dimensions` is sent to
 * the OpenAI embeddings API so N is pinned regardless of the model's native
 * size, and is the value MS-2's `vector(N)` column must match. Overriding to
 * `text-embedding-3-large` (3072) is a config-only change; the re-embed/backfill
 * path (FR-E5) migrates existing rows.
 */
export interface EmbeddingConfig {
  provider: string;
  model: string;
  dimensions: number;
  apiKey?: string;
  baseUrl?: string;
  requestTimeoutMs: number;
}

/**
 * Memory Engine knobs (Phase 2 / DAI-121). Retrieval (MS-3 / DAI-148): `enabled`
 * toggles the no-op fallback; `retrievalTopK` and `cosineThreshold` bound
 * relevance; `contextCharBudget` caps injected memory text (≈4 chars/token).
 * Extraction (MS-4 / DAI-149): `extractionEnabled` gates the BullMQ worker (off
 * unless Redis is configured); `highConfidenceThreshold` routes facts at/above
 * it to `active`, the rest to `pending_review`. Cost control (MS-6 / DAI-151):
 * `extractionDailyBudget` caps new extraction runs per user per UTC day.
 */
export interface MemoryConfig {
  enabled: boolean;
  retrievalTopK: number;
  cosineThreshold: number;
  contextCharBudget: number;
  extractionEnabled: boolean;
  highConfidenceThreshold: number;
  extractionDailyBudget: number;
}

/** Redis connection for the BullMQ extraction queue (MS-4 / DAI-149). */
export interface RedisConfig {
  host: string;
  port: number;
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

/** Parse a float env var, falling back when unset/blank/non-numeric. */
const toFloat = (value: string | undefined, fallback: number): number => {
  if (value === undefined || value.trim() === '') return fallback;
  const n = parseFloat(value);
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
    // Decision #1: default to the OpenAI-compatible provider when a key is
    // available, else the keyless deterministic stub (local/test). An explicit
    // LLM_PROVIDER always wins; `LLM_BASE_URL` repoints OpenAI at a proxy.
    provider:
      process.env.LLM_PROVIDER ?? (process.env.LLM_API_KEY ? 'openai' : 'stub'),
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
  embedding: {
    // Mirrors the LLM provider resolution: `openai` when a key is present
    // (shared LLM_API_KEY), else the keyless deterministic stub. An explicit
    // EMBEDDING_PROVIDER always wins.
    provider:
      process.env.EMBEDDING_PROVIDER ??
      (process.env.LLM_API_KEY ? 'openai' : 'stub'),
    // Locked working default (DAI-146): text-embedding-3-small @ N=1536, cosine.
    model: process.env.EMBEDDING_MODEL?.trim() || 'text-embedding-3-small',
    dimensions: toInt(process.env.EMBEDDING_DIMENSIONS, 1536),
    // Shares the Decision-#1 OpenAI credentials/base with the chat provider.
    apiKey: process.env.LLM_API_KEY,
    baseUrl: process.env.LLM_BASE_URL,
    // Defaults to the shared LLM timeout unless an embedding-specific one is set.
    requestTimeoutMs: toInt(
      process.env.EMBEDDING_REQUEST_TIMEOUT_MS,
      toInt(process.env.LLM_REQUEST_TIMEOUT_MS, 30000),
    ),
  } satisfies EmbeddingConfig,
  memory: {
    enabled: !['0', 'false', 'no', 'off'].includes(
      (process.env.MEMORY_RETRIEVAL_ENABLED ?? 'true').toLowerCase(),
    ),
    retrievalTopK: toInt(process.env.MEMORY_RETRIEVAL_TOP_K, 5),
    cosineThreshold: toFloat(process.env.MEMORY_COSINE_THRESHOLD, 0.75),
    contextCharBudget: toInt(process.env.MEMORY_CONTEXT_CHAR_BUDGET, 1200),
    // Off by default: the worker needs Redis, so enable it explicitly.
    extractionEnabled: ['1', 'true', 'yes', 'on'].includes(
      (process.env.MEMORY_EXTRACTION_ENABLED ?? 'false').toLowerCase(),
    ),
    highConfidenceThreshold: toFloat(
      process.env.MEMORY_HIGH_CONFIDENCE_THRESHOLD,
      0.7,
    ),
    extractionDailyBudget: toInt(process.env.MEMORY_EXTRACTION_DAILY_BUDGET, 200),
  } satisfies MemoryConfig,
  redis: {
    host: process.env.REDIS_HOST ?? 'localhost',
    port: toInt(process.env.REDIS_PORT, 6379),
  } satisfies RedisConfig,
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
