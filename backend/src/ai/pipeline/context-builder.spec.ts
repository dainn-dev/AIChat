import { ContextBuilder, ReplyContext } from './context-builder';
import { Tone } from '../enums/tone.enum';
import { MessageSender } from '../enums/source.enum';
import { OutputLang } from '../enums/output-lang.enum';

/**
 * Output-language instruction (Android Phase 0): the keyboard's EN/VI toggle
 * must pin the suggestion language; without it the model mirrors the chat.
 */
describe('ContextBuilder language instructions', () => {
  const builder = new ContextBuilder();
  const base: ReplyContext = {
    messages: [{ sender: MessageSender.Them, content: 'tối nay rảnh không?' }],
    memories: [],
    tone: Tone.Friendly,
    count: 3,
  };

  it('instructs English when lang=en', () => {
    const { system } = builder.buildReplyPrompt({
      ...base,
      lang: OutputLang.En,
    });
    expect(system).toContain('Write every reply in English');
    expect(system).not.toContain('same language as the conversation');
  });

  it('instructs Vietnamese when lang=vi', () => {
    const { system } = builder.buildReplyPrompt({
      ...base,
      lang: OutputLang.Vi,
    });
    expect(system).toContain('Write every reply in Vietnamese');
  });

  it("mirrors the conversation's language when lang is omitted", () => {
    const { system } = builder.buildReplyPrompt(base);
    expect(system).toContain('same language as the conversation');
    expect(system).not.toMatch(/in (English|Vietnamese)/);
  });

  it('keeps the JSON contract in the reply prompt', () => {
    const { system } = builder.buildReplyPrompt({
      ...base,
      lang: OutputLang.Vi,
    });
    expect(system).toContain('{"replies":[{"text":"..."}]}');
  });

  it('pins the rewrite language when lang is set', () => {
    const { system } = builder.buildRewritePrompt({
      text: 'ok see u',
      tone: Tone.Professional,
      lang: OutputLang.Vi,
    });
    expect(system).toContain('Write the result in Vietnamese');
  });

  it("keeps the draft's language on rewrite when lang is omitted", () => {
    const { system } = builder.buildRewritePrompt({
      text: 'ok see u',
      tone: Tone.Professional,
    });
    expect(system).toContain("Keep the draft's original language.");
  });
});
