# Contact-Specific Conversation Style — Design

## Intent
Make suggested replies feel more natural for each contact and prevent incorrect speaker perspective, pronouns, or forms of address by retaining the full two-sided conversation and learning style from the user's own messages.

## Goals
- Preserve message content with explicit `me` / `them` speaker labels in persisted conversations.
- Build a per-contact style profile from `me` messages only; use `them` messages as context, never as the user's voice.
- Use recent conversation history plus the per-contact profile when generating suggestions.
- Keep current opt-in, owner-scoped deletion, retention, and privacy safeguards.

## Non-goals
- Enable conversation persistence without explicit memory opt-in.
- Learn the user's voice from the other participant's messages.
- Extend persistence to surfaces without a suitable contact identity and consent-aware storage path in this change.

## Constraints
- Existing keyboard capture stores conversations by `(user, platform, contact_label)` when memory opt-in is enabled and messages are labeled by sender.
- Existing conversation deletion and opt-out erasure must remove or invalidate dependent learned data consistently.
- Conversation content is sensitive user data; do not expose content in logs or metadata listings.

## Approaches considered
1. Store full conversation only and rely on recent history in each prompt. Simple and faithful, but does not provide a compact learned style profile.
2. Store only a style profile. Efficient at inference, but loses the requested full two-sided conversational history.
3. Store full labeled history and derive a contact-specific style profile from `me` messages. Chosen: preserves the source material and supports more natural suggestions without confusing speakers.

## Design
For opted-in keyboard conversations with an identified contact, continue storing both sides with explicit sender labels and existing retention/erasure behavior. Derive a style profile scoped to that user/contact from the user's (`me`) messages only; participant (`them`) messages may inform context but must not contribute to the user's style. Reply generation receives recent labeled turns and the associated profile, and the prompt explicitly identifies the user's and contact's roles and asks for consistent first-person perspective, appropriate pronouns/forms of address, language, and tone. Deleting the conversation or opting out must erase/invalidate any profile derived solely from that conversation, following existing memory provenance and erasure rules.

## Open implementation decisions
The implementation plan must determine the best fit with the existing memory extraction/provenance model for storing and updating style profiles, and verify whether existing contact-scoped retrieval returns only memories appropriate for voice guidance. Initial scope is the Android keyboard capture path; other surfaces are out of scope until they have reliable contact identity and consent-aware persistence.
