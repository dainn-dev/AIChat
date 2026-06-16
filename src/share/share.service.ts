import { Injectable } from '@nestjs/common';
import { AiSource } from '../ai/enums/source.enum';
import { AuthenticatedUser } from '../auth/jwt-payload';
import { CreateScreenshotDto } from '../screenshots/dto/create-screenshot.dto';
import { ScreenshotsService } from '../screenshots/screenshots.service';
import { ShareAnalyzeResponse } from './dto/share.dto';

/**
 * Share-Menu surface (P3 / DAI-122). A one-round-trip analyze for a screenshot
 * shared from another app: persist the on-device-OCR'd conversation (metered
 * against the screenshot quota; OCR-failure → 422 before any quota is spent),
 * then return the AI analysis tagged `source: share` for analytics/quota
 * attribution. Composes the existing WS-5 ingest + analyze rather than
 * duplicating them — analysis is not separately metered.
 */
@Injectable()
export class ShareService {
  constructor(private readonly screenshots: ScreenshotsService) {}

  async analyze(
    user: AuthenticatedUser,
    dto: CreateScreenshotDto,
  ): Promise<ShareAnalyzeResponse> {
    const created = await this.screenshots.ingest(user.sub, user.tier, dto);
    const analysis = await this.screenshots.analyze(
      user.sub,
      user.tier,
      created.screenshot_id,
      AiSource.Share,
    );
    return {
      screenshot_id: created.screenshot_id,
      conversation_id: created.conversation_id,
      ...analysis,
    };
  }
}
