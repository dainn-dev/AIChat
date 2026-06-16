import { createHash } from 'crypto';
import {
  ConflictException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { InjectDataSource } from '@nestjs/typeorm';
import { DataSource } from 'typeorm';
import { EmbeddingService } from '../ai/embedding/embedding.service';
import {
  CreateMemoryDto,
  ListMemoriesQueryDto,
  MemoryView,
  UpdateMemoryDto,
} from './dto/memory.dto';

interface MemoryRow {
  id: string;
  kind: string;
  content: string;
  contact_label: string | null;
  status: string;
  confidence: number | null;
  source: string;
  source_ref: string | null;
  created_at: Date;
}

const VIEW_COLUMNS = `id, kind, content, contact_label, status, confidence, source, source_ref, created_at`;
const PG_UNIQUE_VIOLATION = '23505';

/**
 * Memory dashboard CRUD (MS-5 / DAI-150, FR-D1..D6) over the MS-2 schema.
 * User-scoped management of the `memories` table that the extraction worker
 * (MS-4) populates and the retriever (MS-3) reads:
 *  - manual creates are embedded (MS-1), `source=manual`, confidence 1.0, active;
 *  - edits to content re-embed; `status` confirms (`active`) / dismisses
 *    (`dismissed`) a review item;
 *  - delete is a hard delete, so the row leaves the HNSW index too (true
 *    erasure, §5.7) and the next retrieval can't surface it.
 * Every query is scoped to the authenticated owner.
 */
@Injectable()
export class MemoriesService {
  constructor(
    @InjectDataSource() private readonly dataSource: DataSource,
    private readonly embeddings: EmbeddingService,
  ) {}

  async list(
    userId: string,
    query: ListMemoriesQueryDto,
  ): Promise<MemoryView[]> {
    const params: unknown[] = [userId];
    const where = [`user_id = $1`];
    if (query.kind) {
      params.push(query.kind);
      where.push(`kind = $${params.length}`);
    }
    if (query.status) {
      params.push(query.status);
      where.push(`status = $${params.length}`);
    }
    if (query.contact_label) {
      params.push(query.contact_label);
      where.push(`contact_label = $${params.length}`);
    }

    const rows: MemoryRow[] = await this.dataSource.query(
      `SELECT ${VIEW_COLUMNS} FROM memories
        WHERE ${where.join(' AND ')}
        ORDER BY kind ASC, created_at DESC`,
      params,
    );
    return rows.map(this.toView);
  }

  async create(userId: string, dto: CreateMemoryDto): Promise<MemoryView> {
    const content = dto.content.trim();
    const embedded = await this.embeddings.embedOrNull(content);
    const embedding = embedded ? `[${embedded.vector.join(',')}]` : null;
    try {
      const rows: MemoryRow[] = await this.dataSource.query(
        `INSERT INTO memories
           (user_id, contact_label, kind, content, embedding, source, source_ref,
            status, confidence, content_hash, embedding_model)
         VALUES ($1, $2, $3, $4, $5::vector, 'manual', 'manual', 'active', 1.0, $6, $7)
         RETURNING ${VIEW_COLUMNS}`,
        [
          userId,
          dto.contact_label ?? null,
          dto.kind,
          content,
          embedding,
          this.hash(content),
          embedded?.model ?? null,
        ],
      );
      return this.toView(rows[0]);
    } catch (err) {
      throw this.mapConflict(err);
    }
  }

  async update(
    userId: string,
    id: string,
    dto: UpdateMemoryDto,
  ): Promise<MemoryView> {
    const existing: MemoryRow[] = await this.dataSource.query(
      `SELECT ${VIEW_COLUMNS} FROM memories WHERE id = $1 AND user_id = $2`,
      [id, userId],
    );
    if (existing.length === 0) throw this.notFound();

    const sets: string[] = ['updated_at = now()'];
    const params: unknown[] = [];
    const push = (clause: string, value: unknown) => {
      params.push(value);
      sets.push(`${clause} = $${params.length}`);
    };

    if (dto.kind !== undefined) push('kind', dto.kind);
    if (dto.contact_label !== undefined)
      push('contact_label', dto.contact_label);
    if (dto.status !== undefined) push('status', dto.status);
    if (
      dto.content !== undefined &&
      dto.content.trim() !== existing[0].content
    ) {
      const content = dto.content.trim();
      push('content', content);
      push('content_hash', this.hash(content));
      // Re-embed on content change (MS-1) so retrieval reflects the edit.
      const embedded = await this.embeddings.embedOrNull(content);
      params.push(embedded ? `[${embedded.vector.join(',')}]` : null);
      sets.push(`embedding = $${params.length}::vector`);
      push('embedding_model', embedded?.model ?? null);
    }

    if (sets.length === 1) return this.toView(existing[0]); // only updated_at → no-op patch

    params.push(id, userId);
    try {
      // TypeORM returns [rows, affectedCount] for UPDATE ... RETURNING.
      const [rows]: [MemoryRow[], number] = await this.dataSource.query(
        `UPDATE memories SET ${sets.join(', ')}
          WHERE id = $${params.length - 1} AND user_id = $${params.length}
          RETURNING ${VIEW_COLUMNS}`,
        params,
      );
      if (rows.length === 0) throw this.notFound();
      return this.toView(rows[0]);
    } catch (err) {
      throw this.mapConflict(err);
    }
  }

  async remove(userId: string, id: string): Promise<void> {
    // Hard delete → the row also leaves the HNSW index (true erasure, §5.7).
    // TypeORM returns [rows, affectedCount] for DELETE ... RETURNING.
    const [rows]: [Array<{ id: string }>, number] = await this.dataSource.query(
      `DELETE FROM memories WHERE id = $1 AND user_id = $2 RETURNING id`,
      [id, userId],
    );
    if (rows.length === 0) throw this.notFound();
  }

  private toView = (row: MemoryRow): MemoryView => ({
    id: row.id,
    kind: row.kind,
    content: row.content,
    contact_label: row.contact_label,
    status: row.status,
    confidence: row.confidence === null ? null : Number(row.confidence),
    source: row.source,
    source_ref: row.source_ref,
    created_at:
      row.created_at instanceof Date
        ? row.created_at.toISOString()
        : String(row.created_at),
  });

  private hash(content: string): string {
    const normalized = content.toLowerCase().replace(/\s+/g, ' ').trim();
    return createHash('sha256').update(normalized).digest('hex');
  }

  private notFound(): NotFoundException {
    return new NotFoundException({
      code: 'MEMORY_NOT_FOUND',
      message: 'Memory not found.',
    });
  }

  private mapConflict(err: unknown): unknown {
    if ((err as { code?: string })?.code === PG_UNIQUE_VIOLATION) {
      return new ConflictException({
        code: 'MEMORY_DUPLICATE',
        message: 'An identical memory already exists for this contact.',
      });
    }
    return err;
  }
}
