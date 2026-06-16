import {
  Body,
  Controller,
  HttpCode,
  HttpStatus,
  Param,
  ParseUUIDPipe,
  Post,
  UseGuards,
} from '@nestjs/common';
import { AnalyzeResponse } from '../ai/dto/analyze.dto';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { AuthenticatedUser } from '../auth/jwt-payload';
import { CreateScreenshotDto } from './dto/create-screenshot.dto';
import { CreateScreenshotResponse } from './dto/screenshot-response';
import { ScreenshotsService } from './screenshots.service';

/**
 * Screenshot endpoints (DAI-124 §3, WS-5). Both routes are auth-protected —
 * screenshots are tied to the authenticated owner, and unauthenticated
 * requests are rejected with 401 (AC-A5).
 */
@Controller('screenshots')
@UseGuards(JwtAuthGuard)
export class ScreenshotsController {
  constructor(private readonly screenshots: ScreenshotsService) {}

  @Post()
  @HttpCode(HttpStatus.CREATED)
  create(
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: CreateScreenshotDto,
  ): Promise<CreateScreenshotResponse> {
    return this.screenshots.ingest(user.sub, user.tier, dto);
  }

  @Post(':id/analyze')
  @HttpCode(HttpStatus.OK)
  analyze(
    @CurrentUser() user: AuthenticatedUser,
    @Param('id', ParseUUIDPipe) id: string,
  ): Promise<AnalyzeResponse> {
    return this.screenshots.analyze(user.sub, user.tier, id);
  }
}
