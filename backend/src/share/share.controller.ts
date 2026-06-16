import {
  Body,
  Controller,
  HttpCode,
  HttpStatus,
  Post,
  UseGuards,
} from '@nestjs/common';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { AuthenticatedUser } from '../auth/jwt-payload';
import { CreateScreenshotDto } from '../screenshots/dto/create-screenshot.dto';
import { ShareAnalyzeResponse } from './dto/share.dto';
import { ShareService } from './share.service';

/**
 * Share-Menu backend contract (P3 / DAI-122) — the gate for the Phase-3 share
 * extensions, versioned under `/v1`. Auth-protected, quota-attributed (shared
 * screenshot counter), and tagged `source: share`. See docs/share-api.md.
 */
@Controller('v1/share')
@UseGuards(JwtAuthGuard)
export class ShareController {
  constructor(private readonly share: ShareService) {}

  @Post('analyze')
  @HttpCode(HttpStatus.OK)
  analyze(
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: CreateScreenshotDto,
  ): Promise<ShareAnalyzeResponse> {
    return this.share.analyze(user, dto);
  }
}
