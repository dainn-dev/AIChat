import { AnalyzeResponse } from '../../ai/dto/analyze.dto';

/**
 * `POST /v1/share/analyze` response (P3 Share-Menu contract): the persisted
 * ids plus the standard analysis envelope (summary / interest_score /
 * suggested_replies / red_flags / usage). The request body reuses
 * `CreateScreenshotDto` — the share extension sends the same on-device-OCR
 * payload as WS-5.
 */
export interface ShareAnalyzeResponse extends AnalyzeResponse {
  screenshot_id: string;
  conversation_id: string;
}
