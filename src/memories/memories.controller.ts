import {
  Body,
  Controller,
  Delete,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  ParseUUIDPipe,
  Patch,
  Post,
  Query,
  UseGuards,
} from '@nestjs/common';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { AuthenticatedUser } from '../auth/jwt-payload';
import {
  CreateMemoryDto,
  ListMemoriesQueryDto,
  MemoryView,
  UpdateMemoryDto,
} from './dto/memory.dto';
import { MemoriesService } from './memories.service';

/**
 * Memory dashboard endpoints (MS-5 / DAI-150, FR-D1..D6). Auth-protected and
 * owner-scoped — every operation acts only on the authenticated user's
 * memories. Backs the Flutter dashboard (CRUD + the pending_review queue).
 */
@Controller('memories')
@UseGuards(JwtAuthGuard)
export class MemoriesController {
  constructor(private readonly memories: MemoriesService) {}

  @Get()
  list(
    @CurrentUser() user: AuthenticatedUser,
    @Query() query: ListMemoriesQueryDto,
  ): Promise<MemoryView[]> {
    return this.memories.list(user.sub, query);
  }

  @Post()
  @HttpCode(HttpStatus.CREATED)
  create(
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: CreateMemoryDto,
  ): Promise<MemoryView> {
    return this.memories.create(user.sub, dto);
  }

  @Patch(':id')
  update(
    @CurrentUser() user: AuthenticatedUser,
    @Param('id', ParseUUIDPipe) id: string,
    @Body() dto: UpdateMemoryDto,
  ): Promise<MemoryView> {
    return this.memories.update(user.sub, id, dto);
  }

  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  remove(
    @CurrentUser() user: AuthenticatedUser,
    @Param('id', ParseUUIDPipe) id: string,
  ): Promise<void> {
    return this.memories.remove(user.sub, id);
  }
}
