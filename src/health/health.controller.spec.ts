import { Test, TestingModule } from '@nestjs/testing';
import { HealthCheckService, TypeOrmHealthIndicator } from '@nestjs/terminus';
import { HealthController } from './health.controller';

describe('HealthController', () => {
  let controller: HealthController;
  const healthCheck = jest.fn();
  const pingCheck = jest.fn();

  beforeEach(async () => {
    const module: TestingModule = await Test.createTestingModule({
      controllers: [HealthController],
      providers: [
        { provide: HealthCheckService, useValue: { check: healthCheck } },
        { provide: TypeOrmHealthIndicator, useValue: { pingCheck } },
      ],
    }).compile();

    controller = module.get(HealthController);
    healthCheck.mockReset();
    pingCheck.mockReset();
  });

  it('liveness runs no indicators (does not depend on the DB)', async () => {
    healthCheck.mockResolvedValue({ status: 'ok' });
    const result = await controller.liveness();
    expect(result).toEqual({ status: 'ok' });
    expect(healthCheck).toHaveBeenCalledWith([]);
  });

  it('readiness pings the database', async () => {
    healthCheck.mockResolvedValue({ status: 'ok' });
    await controller.readiness();
    expect(healthCheck).toHaveBeenCalledWith([expect.any(Function)]);
    // Invoke the indicator thunk to confirm it pings the DB.
    const [indicators] = healthCheck.mock.calls[0];
    await indicators[0]();
    expect(pingCheck).toHaveBeenCalledWith('database', { timeout: 1500 });
  });
});
