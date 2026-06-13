import {
  CanActivate,
  ExecutionContext,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import { Request } from 'express';
import { AuthConfig } from '../../config/configuration';
import { AccessTokenPayload } from '../jwt-payload';

/**
 * Guards protected routes (FR-A5): requires a valid `Authorization: Bearer
 * <access_token>`. On success the decoded payload is attached to
 * `request.user`; on any failure a 401 is thrown.
 */
@Injectable()
export class JwtAuthGuard implements CanActivate {
  constructor(
    private readonly jwt: JwtService,
    private readonly config: ConfigService,
  ) {}

  canActivate(context: ExecutionContext): boolean {
    const request = context.switchToHttp().getRequest<Request>();
    const token = this.extractBearerToken(request);

    if (!token) {
      throw new UnauthorizedException('Missing or malformed bearer token.');
    }

    const { jwtAccessSecret } = this.config.getOrThrow<AuthConfig>('auth');

    try {
      const payload = this.jwt.verify<AccessTokenPayload>(token, {
        secret: jwtAccessSecret,
      });
      (request as Request & { user: AccessTokenPayload }).user = payload;
      return true;
    } catch {
      throw new UnauthorizedException('Invalid or expired access token.');
    }
  }

  private extractBearerToken(request: Request): string | null {
    const header = request.headers.authorization;
    if (!header) return null;
    const [scheme, value] = header.split(' ');
    if (scheme?.toLowerCase() !== 'bearer' || !value) return null;
    return value.trim() || null;
  }
}
