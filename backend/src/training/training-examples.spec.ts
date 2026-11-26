import { ContextBuilder } from '../ai/pipeline/context-builder';
import { NormalizedMessage } from '../ai/pipeline/normalizer';
import { MessageSender } from '../ai/enums/source.enum';
import { SensitiveDataFilter } from '../memory/privacy/sensitive-data.filter';
import { buildExamples, FineTuneExample } from './training-examples';

const me = (content: string): NormalizedMessage => ({
  sender: MessageSender.Me,
  content,
});
const them = (content: string): NormalizedMessage => ({
  sender: MessageSender.Them,
  content,
});

const build = (messages: NormalizedMessage[], contextMessages = 20) =>
  buildExamples(messages, new SensitiveDataFilter(), new ContextBuilder(), {
    contextMessages,
    maxReplyBubbles: 3,
    maxReplyChars: 500,
  });

const reply = (example: FineTuneExample) =>
  (JSON.parse(example.messages[2].content) as { replies: { text: string }[] })
    .replies[0].text;

describe('buildExamples', () => {
  it('makes one example per reply to "them", in the inference prompt format', () => {
    const { examples, stats } = build([
      them('Tối nay đi ăn không?'),
      me('Đi chứ'),
      them('7h nha'),
      me('Ok e'),
    ]);
    expect(stats.examples).toBe(2);
    expect(examples.map(reply)).toEqual(['Đi chứ', 'Ok e']);

    const [system, user, assistant] = examples[1].messages;
    expect(system.role).toBe('system');
    expect(system.content).toContain('drafts replies on behalf of "me"');
    expect(user.role).toBe('user');
    expect(user.content).toContain('them: Tối nay đi ăn không?');
    expect(user.content).toContain('me: Đi chứ');
    expect(user.content).toContain('them: 7h nha');
    expect(user.content).not.toContain('Ok e');
    expect(assistant.role).toBe('assistant');
  });

  it('merges consecutive "me" bubbles into one reply, up to the bubble limit', () => {
    const { examples } = build([
      them('Sao rồi?'),
      me('Ổn'),
      me('Mới về tới'),
      me('Mệt xíu'),
      me('Bubble thứ tư'),
    ]);
    expect(examples.map(reply)).toEqual(['Ổn\nMới về tới\nMệt xíu']);
  });

  it('skips "me" messages that do not follow "them"', () => {
    const { examples } = build([me('Alo'), me('Có đó không')]);
    expect(examples).toEqual([]);
  });

  it('keeps only the most recent context messages', () => {
    const { examples } = build(
      [them('một'), me('hai'), them('ba'), me('bốn')],
      2,
    );
    const user = examples[1].messages[1].content;
    expect(user).toContain('me: hai');
    expect(user).toContain('them: ba');
    expect(user).not.toContain('một');
  });

  it('masks identifiers in both context and reply', () => {
    const { examples } = build([
      them('Gọi anh số 0912 345 678 nhé'),
      me('Mail em là an@example.com'),
    ]);
    const line = JSON.stringify(examples[0]);
    expect(line).not.toContain('0912 345 678');
    expect(line).not.toContain('an@example.com');
    expect(line).toContain('[redacted-phone]');
    expect(line).toContain('[redacted-email]');
  });

  it('skips a window that contains a sensitive drop-category message', () => {
    const { examples, stats } = build(
      [
        them('My diagnosis came back'),
        me('Oh no'),
        them('Đi cafe không?'),
        me('Đi'),
      ],
      1,
    );
    expect(examples.map(reply)).toEqual(['Đi']);
    expect(stats.skippedSensitive).toBe(1);
  });

  it('skips replies over the length limit', () => {
    const { examples, stats } = build([them('Kể đi'), me('x'.repeat(501))]);
    expect(examples).toEqual([]);
    expect(stats.skippedTooLong).toBe(1);
  });

  it('never puts anything but prompt and reply text in the example', () => {
    const { examples } = build([them('Hi'), me('Hello')]);
    expect(Object.keys(examples[0])).toEqual(['messages']);
    expect(examples[0].messages.map((m) => Object.keys(m))).toEqual([
      ['role', 'content'],
      ['role', 'content'],
      ['role', 'content'],
    ]);
  });
});
