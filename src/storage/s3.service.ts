import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import {
  GetObjectCommand,
  PutObjectCommand,
  S3Client,
} from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { S3Config } from '../config/configuration';

/**
 * Thin wrapper around the AWS S3 client used for screenshot storage (consumed
 * later by WS-5). Supports custom endpoints (MinIO / LocalStack) and path-style
 * addressing for local development.
 */
@Injectable()
export class S3Service implements OnModuleInit {
  private readonly logger = new Logger(S3Service.name);
  private readonly cfg: S3Config;
  private readonly client: S3Client;

  constructor(config: ConfigService) {
    this.cfg = config.getOrThrow<S3Config>('s3');
    this.client = new S3Client({
      region: this.cfg.region,
      ...(this.cfg.endpoint ? { endpoint: this.cfg.endpoint } : {}),
      forcePathStyle: this.cfg.forcePathStyle,
      ...(this.cfg.accessKeyId && this.cfg.secretAccessKey
        ? {
            credentials: {
              accessKeyId: this.cfg.accessKeyId,
              secretAccessKey: this.cfg.secretAccessKey,
            },
          }
        : {}),
    });
  }

  onModuleInit(): void {
    if (!this.cfg.accessKeyId || !this.cfg.secretAccessKey) {
      this.logger.warn(
        'S3 credentials are not configured; falling back to the default AWS credential chain.',
      );
    }
  }

  get bucket(): string {
    return this.cfg.bucket;
  }

  async putObject(
    key: string,
    body: Buffer | Uint8Array | string,
    contentType?: string,
  ): Promise<void> {
    await this.client.send(
      new PutObjectCommand({
        Bucket: this.cfg.bucket,
        Key: key,
        Body: body,
        ...(contentType ? { ContentType: contentType } : {}),
      }),
    );
  }

  /** Presigned GET URL for reading a stored object (default 15 min TTL). */
  async getPresignedUrl(key: string, expiresInSeconds = 900): Promise<string> {
    return getSignedUrl(
      this.client,
      new GetObjectCommand({ Bucket: this.cfg.bucket, Key: key }),
      { expiresIn: expiresInSeconds },
    );
  }
}
