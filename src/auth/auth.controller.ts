import { Body, Controller, HttpCode, HttpStatus, Post } from '@nestjs/common';
import { AuthService } from './auth.service';
import { LoginDto } from './dto/login.dto';
import { LogoutDto } from './dto/logout.dto';
import { RefreshDto } from './dto/refresh.dto';
import { SignupDto } from './dto/signup.dto';
import { AuthTokensResponse, RefreshResponse } from './dto/auth-response';

/**
 * Auth surface (DAI-124 §3). All routes are public — they are how clients
 * obtain credentials. Protected routes use {@link JwtAuthGuard} instead.
 */
@Controller('auth')
export class AuthController {
  constructor(private readonly auth: AuthService) {}

  // 201 Created (Nest default for POST) — AC-A1.
  @Post('signup')
  signup(@Body() dto: SignupDto): Promise<AuthTokensResponse> {
    return this.auth.signup({
      email: dto.email,
      password: dto.password,
      displayName: dto.display_name,
    });
  }

  @Post('login')
  @HttpCode(HttpStatus.OK)
  login(@Body() dto: LoginDto): Promise<AuthTokensResponse> {
    return this.auth.login({ email: dto.email, password: dto.password });
  }

  @Post('refresh')
  @HttpCode(HttpStatus.OK)
  refresh(@Body() dto: RefreshDto): Promise<RefreshResponse> {
    return this.auth.refresh(dto.refresh_token);
  }

  @Post('logout')
  @HttpCode(HttpStatus.NO_CONTENT)
  async logout(@Body() dto: LogoutDto): Promise<void> {
    await this.auth.logout(dto.refresh_token);
  }
}
