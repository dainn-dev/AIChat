import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { TypeOrmModule } from '@nestjs/typeorm';
import { AuthController } from './auth.controller';
import { AuthService } from './auth.service';
import { AuthSession } from './entities/auth-session.entity';
import { User } from './entities/user.entity';
import { JwtAuthGuard } from './guards/jwt-auth.guard';
import { MeController } from './me.controller';
import { UsageModule } from '../usage/usage.module';

/**
 * Auth workstream (WS-3 / DAI-127): signup, login, session refresh-token
 * rotation, logout revocation, and `GET /me`.
 *
 * `JwtAuthGuard` is exported so other workstreams (WS-4 conversations / AI
 * pipeline, WS-5 screenshots) can protect their own routes with it.
 *
 * `UsageModule` is imported so `GET /me` reads today's usage from the canonical
 * metric-based `usage_counters` via `UsageService` — the same source the quota
 * enforcement path uses (DAI-145).
 */
@Module({
  imports: [
    TypeOrmModule.forFeature([User, AuthSession]),
    // Secrets/TTLs are passed per-call from AuthConfig, so no static
    // registration options are needed here.
    JwtModule.register({}),
    UsageModule,
  ],
  controllers: [AuthController, MeController],
  providers: [AuthService, JwtAuthGuard],
  exports: [AuthService, JwtAuthGuard],
})
export class AuthModule {}
