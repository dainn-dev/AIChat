import { Body, Controller, Get, Patch, UseGuards } from '@nestjs/common';
import { AuthService } from './auth.service';
import { CurrentUser } from './decorators/current-user.decorator';
import { MeResponse, SettingsResponse } from './dto/auth-response';
import { UpdateSettingsDto } from './dto/update-settings.dto';
import { JwtAuthGuard } from './guards/jwt-auth.guard';
import { AuthenticatedUser } from './jwt-payload';

/**
 * The authenticated user's own resources:
 *  - `GET /me` (DAI-124 §3) — the canonical "who am I + my quota" endpoint,
 *    now also returning `settings`;
 *  - `PATCH /me/settings` (Android Phase 0) — user-controlled settings
 *    (`memory_opt_in`).
 * Protected — unauthenticated requests are rejected with 401 (AC-A5).
 */
@Controller()
@UseGuards(JwtAuthGuard)
export class MeController {
  constructor(private readonly auth: AuthService) {}

  @Get('me')
  me(@CurrentUser() user: AuthenticatedUser): Promise<MeResponse> {
    return this.auth.getMe(user.sub);
  }

  /** `PATCH /me/settings` — user-controlled settings (Android Phase 0). */
  @Patch('me/settings')
  updateSettings(
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: UpdateSettingsDto,
  ): Promise<SettingsResponse> {
    return this.auth.updateSettings(user.sub, dto);
  }
}
