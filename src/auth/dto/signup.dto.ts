import {
  IsEmail,
  IsOptional,
  IsString,
  MaxLength,
  MinLength,
} from 'class-validator';

export class SignupDto {
  @IsEmail()
  @MaxLength(320)
  email: string;

  // FR-A1: minimum 8 characters. Upper bound guards against bcrypt's 72-byte
  // truncation surprise and abusive payloads.
  @IsString()
  @MinLength(8)
  @MaxLength(128)
  password: string;

  @IsOptional()
  @IsString()
  @MaxLength(80)
  display_name?: string;
}
