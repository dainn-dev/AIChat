/**
 * The usage block returned by `GET /me` and echoed in AI endpoint responses.
 * A `null` limit means "unlimited" (Pro tier).
 */
export interface UsageSummary {
  replies_used: number;
  replies_limit: number | null;
  screenshots_used: number;
  screenshots_limit: number | null;
}
