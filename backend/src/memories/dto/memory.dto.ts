import {
  IsIn,
  IsOptional,
  IsString,
  MaxLength,
  MinLength,
} from 'class-validator';

/** Phase-2 memory kinds (§5.8), shared by the dashboard DTOs. */
export const MEMORY_KINDS = ['interest', 'job', 'birthday', 'fact'] as const;
export type MemoryKind = (typeof MEMORY_KINDS)[number];

/** Statuses a client may filter by / transition to. */
export const MEMORY_STATUSES = [
  'active',
  'pending_review',
  'superseded',
  'dismissed',
] as const;
export type MemoryStatus = (typeof MEMORY_STATUSES)[number];

/** `GET /memories` filters (FR-D1). All optional; omitted = no filter. */
export class ListMemoriesQueryDto {
  @IsOptional()
  @IsIn(MEMORY_KINDS)
  kind?: MemoryKind;

  @IsOptional()
  @IsString()
  @MaxLength(120)
  contact_label?: string;

  @IsOptional()
  @IsIn(MEMORY_STATUSES)
  status?: MemoryStatus;
}

/** `POST /memories` — manual memory (FR-D2): embedded, source=manual, conf=1. */
export class CreateMemoryDto {
  @IsIn(MEMORY_KINDS)
  kind!: MemoryKind;

  @IsString()
  @MinLength(1)
  @MaxLength(2000)
  content!: string;

  @IsOptional()
  @IsString()
  @MaxLength(120)
  contact_label?: string;
}

/**
 * `PATCH /memories/:id` (FR-D3). Content/kind edits re-embed via MS-1; `status`
 * confirms (`active`) or dismisses (`dismissed`) a pending review item. All
 * fields optional — only what's sent is changed.
 */
export class UpdateMemoryDto {
  @IsOptional()
  @IsIn(MEMORY_KINDS)
  kind?: MemoryKind;

  @IsOptional()
  @IsString()
  @MinLength(1)
  @MaxLength(2000)
  content?: string;

  @IsOptional()
  @IsString()
  @MaxLength(120)
  contact_label?: string;

  /** Review action: `active` = confirm, `dismissed` = dismiss. */
  @IsOptional()
  @IsIn(['active', 'dismissed'])
  status?: 'active' | 'dismissed';
}

/** Dashboard view of a memory row (provenance included). */
export interface MemoryView {
  id: string;
  kind: string;
  content: string;
  contact_label: string | null;
  status: string;
  confidence: number | null;
  /** Provenance type: 'manual' (dashboard) or 'extracted' (worker). */
  source: string;
  source_ref: string | null;
  created_at: string;
}
