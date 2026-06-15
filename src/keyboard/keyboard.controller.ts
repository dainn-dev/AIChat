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
import {
  KeyboardReplyDto,
  KeyboardReplyResponse,
  KeyboardRewriteDto,
  KeyboardTextResponse,
  KeyboardTranslateDto,
} from './dto/keyboard.dto';
import { KeyboardService } from './keyboard.service';

/**
 * Keyboard backend contract (P4-1 / DAI-136) — the GATE for Phase 4. All routes
 * are auth-protected, quota-enforced (shared per-user daily counter), and tagged
 * `source: keyboard`. Versioned under `/v1` so the keyboard can pin a contract.
 * See docs/keyboard-api.md.
 */
@Controller('v1/keyboard')
@UseGuards(JwtAuthGuard)
export class KeyboardController {
  constructor(private readonly keyboard: KeyboardService) {}

  @Post('reply')
  @HttpCode(HttpStatus.OK)
  reply(
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: KeyboardReplyDto,
  ): Promise<KeyboardReplyResponse> {
    return this.keyboard.reply(user, dto);
  }

  @Post('rewrite')
  @HttpCode(HttpStatus.OK)
  rewrite(
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: KeyboardRewriteDto,
  ): Promise<KeyboardTextResponse> {
    return this.keyboard.rewrite(user, dto);
  }

  @Post('translate')
  @HttpCode(HttpStatus.OK)
  translate(
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: KeyboardTranslateDto,
  ): Promise<KeyboardTextResponse> {
    return this.keyboard.translate(user, dto);
  }
}
