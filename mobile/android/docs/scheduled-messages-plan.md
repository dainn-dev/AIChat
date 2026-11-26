# Kế hoạch: Hẹn giờ nhắn tin (Scheduled Messages)

**Mục tiêu:** Người dùng soạn tin trong Zalo/Messenger bằng bàn phím AIChat, chọn "Hẹn giờ", và đến giờ app **tự gửi** tin đó vào đúng cuộc trò chuyện qua `ChatCaptureService` (Accessibility).

**Quyết định đã chốt (2026-11-26):**
- Gửi **tự động qua Accessibility** (không chỉ nhắc nhở).
- Lưu **chỉ trên máy** (Room + AlarmManager). Không có backend, migration hay API mới. Nội dung tin không gửi lên server.

**Phạm vi v1:** Android, chỉ Zalo (`com.zing.zalo`) và Messenger (`com.facebook.orca`), tức các app đã có trong `SupportedMessenger`. Chỉ gửi văn bản, không gửi ảnh/sticker. Không lặp lại theo lịch.

---

## Bất biến an toàn (không được phá)

1. **Không bao giờ gửi nhầm người.** Chỉ gửi khi tiêu đề toolbar khớp chính xác `contactLabel` đã lưu, và khi tìm kiếm chỉ có đúng **một** kết quả khớp chính xác. Mọi trường hợp mơ hồ thì huỷ tự gửi và chuyển sang fallback.
2. **Không gửi trùng.** Job đang `SENDING` khi process chết sẽ thành `UNCERTAIN` và **không tự retry**. App báo người dùng tự kiểm tra.
3. **Không ghi đè bản nháp.** Nếu ô soạn tin của app chat đang có chữ thì huỷ tự gửi.
4. **Service vẫn "idle by design".** `ChatCaptureService` chỉ đọc hoặc thao tác UI khi có một `SendJob` đang chạy, hoặc khi người dùng bấm "Gợi ý trả lời". Ngoài hai lúc đó, nó vẫn bỏ qua mọi event như hiện nay.
5. **Người dùng luôn huỷ được.** Trước khi chiếm màn hình, app hiện notification đếm ngược 5 giây kèm nút **Huỷ**.
6. **Mọi thất bại đều có fallback.** Notification "Chưa gửi được tin cho X" kèm hai nút [Mở chat] và [Sao chép tin]. Không thất bại nào được im lặng.

---

## Phase 0: Spike và quyết định go/no-go (bắt buộc, khoảng 2 ngày)

Toàn bộ plan phụ thuộc vào việc điều khiển UI bên thứ ba. Cần kiểm chứng trên máy thật trước khi làm tiếp:

- [ ] Từ `ChatCaptureService`, mở Zalo/Messenger bằng `getLaunchIntentForPackage`. Kiểm tra việc khởi chạy activity từ nền: accessibility service đang bound được miễn trừ, cần xác nhận trên Android 14 và 15.
- [ ] Điều hướng đến cuộc trò chuyện qua ô **tìm kiếm** trong app: click node search, `ACTION_SET_TEXT` với tên liên hệ, rồi click kết quả.
- [ ] `ACTION_SET_TEXT` lên ô soạn tin có làm app nhận text không. Messenger/Zalo có thể cần `ACTION_PASTE` qua clipboard.
- [ ] Tìm nút Gửi theo `viewIdResourceName` hoặc `contentDescription`, rồi `ACTION_CLICK`.
- [ ] Xác nhận đã gửi: bubble `me` mới nhất (qua `ChatTranscriptParser`) bằng đúng nội dung.
- [ ] Ghi lại view-id và cấu trúc cây node của từng app (kèm phiên bản app) để làm fixture cho unit test.

**No-go** nếu không ổn định được bước điều hướng ở cả hai app. Khi đó lùi về phương án "nhắc + 1 chạm" (Phase 3 fallback trở thành luồng chính).

---

## Kiến trúc

```
Keyboard (icon "Hẹn giờ" trên AiChatImeBottomBar, cạnh nút AI)
   │ đọc text trong ô soạn tin + snapshot → contactLabel, package
   ▼
ScheduleMessageActivity (transparent, not exported): chọn ngày giờ, xem trước
   ▼
ScheduledMessageRepository (Room) ──► MessageScheduler (AlarmManager)
                                             │ đến giờ
                                             ▼
                                ScheduledMessageAlarmReceiver
                                             │ màn hình khoá? → chờ USER_PRESENT (grace window)
                                             ▼
                          notification đếm ngược 5s [Huỷ]
                                             ▼
               ChatCaptureService.runSendJob(job)  ── SendJobRunner + MessengerDriver
                                             │
                          SENT ✔ / FAILED → fallback notification / UNCERTAIN
```

Package mới: `com.aichat.keyboard.schedule`.

| File | Vai trò |
|---|---|
| `schedule/ScheduledMessage.kt` | Room entity + enum `ScheduleStatus` |
| `schedule/ScheduledMessageDao.kt`, `ScheduleDatabase.kt` | DAO và DB riêng (theo mẫu `ClipboardDatabase`) |
| `schedule/ScheduleStateMachine.kt` | Chuyển trạng thái thuần JVM, có unit test |
| `schedule/MessageScheduler.kt` | Bọc AlarmManager: đặt, huỷ, đặt lại tất cả |
| `schedule/ScheduledMessageAlarmReceiver.kt` | Nhận alarm và khởi động luồng gửi |
| `schedule/ScheduleRescheduleReceiver.kt` | `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED` |
| `schedule/send/SendJobRunner.kt` | Chạy các bước gửi với deadline từng bước (polling cây node mỗi khoảng 150ms) |
| `schedule/send/MessengerDriver.kt` | Interface theo từng app, cùng `ZaloDriver` và `MessengerAppDriver` |
| `schedule/send/NodeMatchers.kt` | Logic thuần tìm search/composer/send/title trên `CapturedNode`, test bằng fixture |
| `schedule/ui/ScheduleMessageActivity.kt` | Màn chọn giờ, mở từ bàn phím |
| `schedule/ui/ScheduledMessagesScreen.kt` | Danh sách, sửa, huỷ, lịch sử trong app settings |
| `schedule/ScheduleNotifications.kt` | Channel "Tin nhắn hẹn giờ": đếm ngược, kết quả, fallback |

### Dữ liệu

```kotlin
@Entity data class ScheduledMessage(
    @PrimaryKey val id: String,          // UUID
    val packageName: String,             // com.zing.zalo | com.facebook.orca
    val contactLabel: String,            // tiêu đề toolbar lúc hẹn (đã trim)
    val text: String,
    val sendAtEpochMs: Long,
    val status: ScheduleStatus,          // SCHEDULED, DUE, SENDING, SENT, FAILED, UNCERTAIN, CANCELLED
    val failureReason: String?,          // enum name, không chứa nội dung tin
    val createdAt: Long, val updatedAt: Long,
)
```

Chuyển trạng thái hợp lệ:
- `SCHEDULED → DUE → SENDING → SENT | FAILED`
- `SCHEDULED → CANCELLED`
- `SENDING → UNCERTAIN` (lúc khởi động app)
- `FAILED`/`UNCERTAIN` không bao giờ quay lại `SENDING` tự động. Người dùng có thể "Gửi lại", và thao tác đó tạo job mới.

### Luồng gửi (SendJobRunner)

Mỗi bước có timeout. Hết giờ thì `FAILED(reason)`.

1. Kiểm tra tiền điều kiện: service đang chạy, màn hình mở khoá và đang bật, không có cuộc gọi đang diễn ra, app đích đã cài. Thiếu điều kiện nào thì fallback.
2. Mở app đích. Nếu chat đang mở đúng `contactLabel` thì sang bước 4.
3. `driver.openConversation(label)`: quay về danh sách chat, mở search, gõ label, chọn đúng một kết quả khớp chính xác.
4. Xác minh title bằng `ChatTranscriptParser.contactLabel` so với `contactLabel`. Không khớp thì `FAILED(WRONG_CHAT)`.
5. Composer phải rỗng, nếu không thì `FAILED(DRAFT_PRESENT)`. Sau đó set text và đọc lại để đối chiếu.
6. Đánh dấu `SENDING` (ghi DB **trước** khi click), rồi click Gửi.
7. Chờ bubble `me` cuối cùng bằng `text`. Thấy thì `SENT`, không thấy thì `UNCERTAIN`, vì không được giả định là chưa gửi.
8. Bấm Back hoặc về Home để trả lại màn hình. Đăng notification kết quả.

Chỉ chạy **một** job tại một thời điểm. Các job đến giờ cùng lúc xếp hàng FIFO.

### Hẹn giờ và các tình huống hệ thống

- `setExactAndAllowWhileIdle` cần `SCHEDULE_EXACT_ALARM`. Từ Android 14 quyền này mặc định bị tắt, nên app dẫn người dùng tới Settings. Nếu không có quyền thì dùng `setWindow` (lệch tối đa 10 phút) và ghi rõ trên UI. **Không** dùng `USE_EXACT_ALARM` vì quyền đó chỉ dành cho app báo thức/lịch.
- **Màn hình khoá:** Accessibility không thao tác được trên keyguard. App đăng notification "Mở khoá để gửi tin cho X" và đăng ký động `ACTION_USER_PRESENT` trong service. Nếu người dùng mở khoá trong grace window (mặc định 30 phút) thì chạy job, quá hạn thì `FAILED(LOCKED)`.
- **Reboot / đổi giờ / cập nhật app:** đặt lại alarm cho mọi job `SCHEDULED`. Job đã quá giờ khi máy khởi động **không** tự gửi muộn mà chuyển sang fallback.
- **Process chết khi đang `SENDING`:** lúc `FlorisApplication` khởi động, job đó chuyển thành `UNCERTAIN` và app gửi notification.
- **Không có mạng:** Zalo/Messenger tự xếp hàng tin. Bubble vẫn xuất hiện nên trạng thái là `SENT`. Ghi chú điều này trong UI.

### Thay đổi với code hiện có

- `ChatCaptureService`: thêm `runSendJob()` và bộ đăng ký `USER_PRESENT` chỉ hoạt động khi có job. Sửa KDoc "idle by design" cho đúng thực tế.
- `aichat_accessibility_service.xml`: giữ nguyên `packageNames` và event type, vì runner dùng polling thay cho event. `performAction` không cần thêm flag. Chỉ cập nhật `description`.
- `ChatCaptureConsentScreen` và chuỗi mô tả service: **bổ sung disclosure** rằng service sẽ mở app chat và gửi tin thay bạn khi bạn hẹn giờ.
- `AiChatImeBottomBar`: thêm nút icon hẹn giờ (`Icons.Outlined.Schedule`) **ngay bên phải nút AI**, cùng kích thước `ButtonWidth`/`IconSize` và cùng style `SmartbarActionKey`. Nút luôn hiện để chiều cao và bố cục thanh dưới không nhảy. Khi ô soạn rỗng hoặc app phía sau không phải `SupportedMessenger`, nút hiện mờ (disabled). Bấm vào lúc đó sẽ hiện toast giải thích. Khi bấm, nút đọc text trong ô soạn và lấy snapshot để có `contactLabel`. Nếu không đọc được tên liên hệ thì báo lỗi và không cho hẹn. Cần thêm chuỗi `aichat__bottom_bar__schedule` ("Hẹn giờ gửi") làm content description.
- `AndroidManifest.xml`: thêm `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, hai receiver (not exported, trừ receiver boot), và `ScheduleMessageActivity` (not exported, theo mẫu `TextStickerMakerActivity`).
- Settings: thêm route "Tin nhắn hẹn giờ" vào `AiChatAccountRoutes`.

**Backend:** không đổi. Tin tự gửi sẽ xuất hiện như tin `me` trong các lần capture sau. Đó là hành vi đúng, không cần xử lý riêng.

---

## Các task

### Task 1: Dữ liệu và state machine
- [ ] Viết entity, DAO, DB và `ScheduleStateMachine`.
- [ ] Unit test: mọi chuyển trạng thái hợp lệ/không hợp lệ, `SENDING → UNCERTAIN` lúc khởi động, không có đường nào tự quay về `SENDING`.

### Task 2: Scheduler
- [ ] Viết `MessageScheduler`, hai receiver và luồng xin quyền exact alarm.
- [ ] Unit test (thuần): tính thời điểm, phân loại job quá hạn lúc boot thành fallback, tách riêng nhánh exact và inexact.

### Task 3: Engine gửi
- [ ] `NodeMatchers` kèm fixture `CapturedNode` ghi từ Phase 0, theo từng app và phiên bản. Test các trường hợp: 0, 1 và 2 kết quả trùng tên; title khác; composer có nháp; không thấy nút Gửi.
- [ ] `ZaloDriver`, `MessengerAppDriver`, `SendJobRunner` với timeout từng bước, `runSendJob` trong service.
- [ ] Notification fallback ([Mở chat] dùng launch intent, [Sao chép tin]).

### Task 4: Điểm vào từ bàn phím
- [x] Icon hẹn giờ cạnh nút AI trong `AiChatImeBottomBar`. Bấm vào sẽ mở popup `ScheduleMessageActivity` gồm: ô **nhập nội dung tin** (điền sẵn chữ đang soạn, sửa được), nút chọn **ngày** và **giờ** (Material3 DatePicker/TimePicker), dòng "Gửi cho X · Zalo" lấy từ `ChatTranscriptParser.recipient`. Lưu vào Room với trạng thái `SCHEDULED`. Icon luôn bật. Khi không nhận ra người nhận, popup báo lỗi và khoá nút Hẹn giờ.
- [ ] Xoá chữ trong ô soạn của app chat sau khi hẹn giờ thành công.
- [x] Từ chối khi giờ cách hiện tại dưới 1 phút, nội dung rỗng hoặc quá 2000 ký tự, hoặc không có người nhận (`ScheduleDraft`, có unit test).
- [ ] Khi Đọc chat chưa bật: thêm nút dẫn tới màn đồng ý quyền ngay trong popup.

### Task 5: Màn quản lý và notification
- [ ] Danh sách được nhóm thành Sắp gửi / Đã gửi / Lỗi. Có thể sửa giờ và nội dung, huỷ, gửi lại (tạo job mới).
- [ ] Notification đếm ngược 5 giây có nút Huỷ. Notification kết quả không hiện toàn văn tin trên màn hình khoá (`VISIBILITY_PRIVATE`).

### Task 6: Disclosure và QA
- [ ] Cập nhật màn consent và chuỗi mô tả service.
- [ ] Ma trận QA trên máy thật: Zalo × Messenger, Android 12/14/15. Các tình huống: màn khoá, Doze qua đêm, reboot, kill app lúc `SENDING`, đang dùng app khác lúc tới giờ, đang có cuộc gọi, hai liên hệ trùng tên, liên hệ đổi tên, có nháp sẵn, mất mạng, chưa cấp exact alarm.

---

## Rủi ro

| Rủi ro | Mức | Giảm thiểu |
|---|---|---|
| Zalo/Messenger đổi UI làm driver hỏng | **Cao** | Selector tập trung trong `NodeMatchers`, dùng fixture theo phiên bản, xác minh title + bubble, luôn có fallback |
| **Chính sách Google Play**: dùng Accessibility để tự thao tác/gửi tin thay người dùng nhiều khả năng vi phạm (với `isAccessibilityTool=false`) | **Cao nếu lên Play** | Hiện app phát hành APK qua GitHub Releases nên ảnh hưởng thấp hơn. Nếu định lên Play, cần đánh giá policy trước Task 3 và có thể tắt tính năng tự gửi bằng build flag |
| Gửi nhầm người | Cao (hậu quả) | Bất biến 1 + 4: khớp chính xác, kết quả duy nhất, xác minh title ngay trước khi click |
| Chiếm màn hình khi người dùng đang thao tác | Trung bình | Đếm ngược 5 giây kèm nút Huỷ. Có thể hoãn khi người dùng đang gõ (IME đang hiển thị ở app khác) |
| OEM diệt alarm hoặc service (Xiaomi, Oppo…) | Trung bình | Hướng dẫn tắt tối ưu pin, phát hiện alarm trễ và chuyển sang fallback |
| Nội dung tin lưu không mã hoá trên máy | Thấp | `allowBackup=false` đã có. Có thể xoá job `SENT` sau 30 ngày |

## Ngoài phạm vi v1
Lặp lại theo lịch, gửi ảnh/sticker, nhiều người nhận, đồng bộ nhiều máy, AI soạn sẵn nội dung lúc hẹn (có thể thêm sau bằng chip AI hiện có), iOS.
