import {
  Column,
  CreateDateColumn,
  Entity,
  Index,
  PrimaryGeneratedColumn,
  Unique,
  UpdateDateColumn,
} from 'typeorm';
import { UsageMetric } from '../usage.constants';

/**
 * One metered counter per (user, metric, day). The unique constraint is what
 * makes the atomic `INSERT ... ON CONFLICT DO UPDATE` reservation in
 * `UsageService` race-free: concurrent requests for the same slot serialize on
 * this row instead of each reading-then-writing past the limit.
 *
 * Keyed on a UTC `usage_date` (a `date`, not a timestamp) so daily reset is a
 * natural consequence of the key changing at UTC midnight — no cron job needed.
 *
 * `user_id` intentionally carries no FK to `users`: the users table lands in a
 * sibling workstream (WS-2) and migration ordering is not guaranteed. It is a
 * plain indexed uuid; referential integrity can be added once schemas merge.
 */
@Entity('usage_counters')
@Unique('usage_counters_unique', ['userId', 'metric', 'usageDate'])
@Index('idx_usage_counters_user_date', ['userId', 'usageDate'])
export class UsageCounter {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column('uuid', { name: 'user_id' })
  userId: string;

  @Column('text')
  metric: UsageMetric;

  @Column('date', { name: 'usage_date' })
  usageDate: string;

  @Column('integer', { default: 0 })
  count: number;

  @CreateDateColumn({ name: 'created_at', type: 'timestamptz' })
  createdAt: Date;

  @UpdateDateColumn({ name: 'updated_at', type: 'timestamptz' })
  updatedAt: Date;
}
