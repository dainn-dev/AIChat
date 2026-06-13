import { Controller, Get } from '@nestjs/common';
import {
  HealthCheck,
  HealthCheckService,
  TypeOrmHealthIndicator,
} from '@nestjs/terminus';

@Controller()
export class HealthController {
  constructor(
    private readonly health: HealthCheckService,
    private readonly db: TypeOrmHealthIndicator,
  ) {}

  /**
   * Liveness probe. Returns 200 as long as the process is up and able to
   * serve requests. Intentionally does NOT depend on the database so a brief
   * DB blip does not cause orchestrators to kill a healthy pod.
   */
  @Get('health')
  @HealthCheck()
  liveness() {
    return this.health.check([]);
  }

  /**
   * Readiness probe. Returns 200 only when downstream dependencies the service
   * needs to handle traffic are reachable — currently the PostgreSQL database.
   */
  @Get('ready')
  @HealthCheck()
  readiness() {
    return this.health.check([
      () => this.db.pingCheck('database', { timeout: 1500 }),
    ]);
  }
}
