import {
  Column,
  CreateDateColumn,
  Entity,
  PrimaryGeneratedColumn,
  Unique,
  UpdateDateColumn,
} from 'typeorm';

/**
 * Per-user, per-day accumulated LLM spend in micro-USD (1e-6 USD). Backs the
 * spend cap that bounds abuse on the Pro "unlimited" plan (DAI-124 §5.10).
 *
 * Stored as `bigint` micro-USD so token costs (fractions of a cent) accumulate
 * without floating-point drift. TypeORM maps `bigint` to a JS string on read;
 * `UsageService` parses it back to a number.
 *
 * Same daily-reset-by-key and no-FK rationale as `UsageCounter`.
 */
@Entity('spend_counters')
@Unique('spend_counters_unique', ['userId', 'usageDate'])
export class SpendCounter {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column('uuid', { name: 'user_id' })
  userId: string;

  @Column('date', { name: 'usage_date' })
  usageDate: string;

  @Column('bigint', { name: 'spent_micro_usd', default: 0 })
  spentMicroUsd: string;

  @CreateDateColumn({ name: 'created_at', type: 'timestamptz' })
  createdAt: Date;

  @UpdateDateColumn({ name: 'updated_at', type: 'timestamptz' })
  updatedAt: Date;
}
