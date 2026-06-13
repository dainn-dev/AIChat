import { Module } from '@nestjs/common';
import { JwtModule } from '@nestjs/jwt';
import { TypeOrmModule } from '@nestjs/typeorm';
import { AuthController } from './auth.controller';
import { AuthService } from './auth.service';
import { AuthSession } from './entities/auth-session.entity';
import { UsageCounter } from './entities/usage-counter.entity';
import { User } from './entities/user.entity';
import { JwtAuthGuard } from './guards/jwt-auth.guard';
import { MeController } from './me.controller';

/**
 * Auth workstream (WS-3 / DAI-127): signup, login, session refresh-token
 * rotation, logout revocation, and `GET /me`.
 *
 * `JwtAuthGuard` is exported so other workstreams (WS-4 conversations / AI
 * pipeline, WS-5 screenshots) can protect their own routes with it.
 */
@Module({
  imports: [
    TypeOrmModule.forFeature([User, AuthSession, UsageCounter]),
    // Secrets/TTLs are passed per-call from AuthConfig, so no static
    // registration options are needed here.
    JwtModule.register({}),
  ],
  controllers: [AuthController, MeController],
  providers: [AuthService, JwtAuthGuard],
  exports: [AuthService, JwtAuthGuard],
})
export class AuthModule {}
