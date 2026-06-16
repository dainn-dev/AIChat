import { IsString, MaxLength, MinLength } from 'class-validator';

export class LogoutDto {
  @IsString()
  @MinLength(1)
  @MaxLength(256)
  refresh_token: string;
}
