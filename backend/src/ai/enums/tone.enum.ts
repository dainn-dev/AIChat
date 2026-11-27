/**
 * Canonical Phase-1 tone set for the Main App, per DAI-124 §1.3 / FR-R1.
 * Keyboard/Share surfaces (Phases 3-4) may extend this; Phase 1 emits only
 * these values. `Formal`/`Casual` added for the keyboard's "Change tone"
 * chips (rewrite only — not offered as a reply tone).
 */
export enum Tone {
  Friendly = 'Friendly',
  Funny = 'Funny',
  Flirty = 'Flirty',
  Professional = 'Professional',
  Mature = 'Mature',
  Formal = 'Formal',
  Casual = 'Casual',
}
