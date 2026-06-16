import {
  Column,
  CreateDateColumn,
  Entity,
  PrimaryGeneratedColumn,
  UpdateDateColumn,
} from 'typeorm';
import { Tier } from '../../common/tiers';

/**
 * Application user. Created on signup with the default `free` tier
 * (DAI-124 FR-A1, FR-A6). `password_hash` is never serialized to clients.
 */
@Entity('users')
export class User {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  // Stored normalized to lowercase by AuthService so uniqueness is effectively
  // case-insensitive without depending on the `citext` extension.
  @Column({ type: 'varchar', length: 320, unique: true })
  email: string;

  @Column({ name: 'password_hash', type: 'text' })
  passwordHash: string;

  @Column({ name: 'display_name', type: 'text', nullable: true })
  displayName: string | null;

  @Column({ type: 'varchar', length: 16, default: Tier.Free })
  tier: Tier;

  @CreateDateColumn({ name: 'created_at', type: 'timestamptz' })
  createdAt: Date;

  @UpdateDateColumn({ name: 'updated_at', type: 'timestamptz' })
  updatedAt: Date;
}
