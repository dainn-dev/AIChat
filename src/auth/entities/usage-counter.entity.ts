import {
  Column,
  CreateDateColumn,
  Entity,
  Index,
  PrimaryGeneratedColumn,
} from 'typeorm';

/**
 * Per-user daily usage counters (DAI-124 §2 `usage_counters`), keyed by
 * `(user_id, date)` for a clean daily quota reset.
 *
 * WS-3 only *reads* this table (for `GET /me`), defaulting to zero when no row
 * exists for the day. The atomic increment / enforcement logic lives in the AI
 * pipeline and screenshot workstreams (WS-4 / WS-5).
 */
@Entity('usage_counters')
@Index('uq_usage_counters_user_date', ['userId', 'date'], { unique: true })
export class UsageCounter {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ name: 'user_id', type: 'uuid' })
  userId: string;

  @Column({ type: 'date' })
  date: string;

  @Column({ name: 'replies_used', type: 'int', default: 0 })
  repliesUsed: number;

  @Column({ name: 'screenshots_used', type: 'int', default: 0 })
  screenshotsUsed: number;

  @CreateDateColumn({ name: 'created_at', type: 'timestamptz' })
  createdAt: Date;
}
