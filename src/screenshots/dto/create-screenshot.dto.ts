import { Type } from 'class-transformer';
import {
  ArrayMaxSize,
  IsArray,
  IsOptional,
  IsString,
  IsUUID,
  MaxLength,
  ValidateNested,
} from 'class-validator';
import { ExtractedMessageDto } from './extracted-message.dto';

/**
 * `POST /screenshots` request (DAI-124 §3).
 *
 * Per epic Decision #2 (on-device ML Kit) the client performs OCR and sends the
 * already-extracted text + messages; the server runs no OCR and stores no raw
 * image (retention policy: do not persist raw images). `ocr_text` is the raw
 * recognized text and `extracted_messages` the structured conversation.
 *
 * Note the deliberate absence of a minimum size on `ocr_text` /
 * `extracted_messages`: a blank/garbled image legitimately produces empty
 * values, and that is the OCR-failure path (AC-O2) — rejected by the service
 * with a clear error *before* any quota is consumed, not by a generic
 * validation 400 here.
 */
export class CreateScreenshotDto {
  @IsString()
  @MaxLength(20000)
  ocr_text!: string;

  @IsArray()
  @ArrayMaxSize(200)
  @ValidateNested({ each: true })
  @Type(() => ExtractedMessageDto)
  extracted_messages!: ExtractedMessageDto[];

  /** Append to an existing conversation; omit to start a new one. */
  @IsOptional()
  @IsUUID()
  conversation_id?: string;

  /** Source chat platform (e.g. `whatsapp`, `imessage`); defaults to unknown. */
  @IsOptional()
  @IsString()
  @MaxLength(64)
  platform?: string;

  @IsOptional()
  @IsString()
  @MaxLength(120)
  contact_label?: string;
}
