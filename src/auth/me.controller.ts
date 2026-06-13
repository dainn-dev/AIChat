import { Controller, Get, UseGuards } from '@nestjs/common';
import { AuthService } from './auth.service';
import { CurrentUser } from './decorators/current-user.decorator';
import { MeResponse } from './dto/auth-response';
import { JwtAuthGuard } from './guards/jwt-auth.guard';
import { AuthenticatedUser } from './jwt-payload';

/**
 * `GET /me` (DAI-124 §3): the canonical "who am I + my quota" endpoint.
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
}
