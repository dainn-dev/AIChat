/**
 * Origin surface of an AI request, per DAI-124 §1.2 / FR-P1. The enum reserves
 * `keyboard` and `share` for Phases 3-4; Phase 1 only ever emits `app`.
 */
export enum AiSource {
  App = 'app',
  Keyboard = 'keyboard',
  Share = 'share',
}

/**
 * The kind of pipeline call, mirrored onto the `ai_requests.type` column
 * (WS-2). Used for audit logging and provider routing.
 */
export enum AiRequestType {
  Reply = 'reply',
  Rewrite = 'rewrite',
  Analysis = 'analysis',
}

/** Who authored a message in a normalized conversation (DAI-124 §1.2 / FR-P2). */
export enum MessageSender {
  Me = 'me',
  Them = 'them',
}
