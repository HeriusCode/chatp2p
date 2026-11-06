# Chat P2P

Ứng dụng Java 17 dùng Swing. Server quản lý tài khoản, trạng thái online, nhóm và lịch sử chat trong PostgreSQL. Tin nhắn riêng, file, ảnh, audio và video vẫn truyền trực tiếp giữa hai client qua TCP P2P. Chat nhóm dùng server làm đường truyền dự phòng khi UDP Multicast không hoạt động.

## Chuẩn bị PostgreSQL

1. Khởi động PostgreSQL trên máy chạy server và tạo database `chatp2p`.
2. Tạo `data/db.properties` trên máy server (file này được Git bỏ qua):

   ```properties
   url=jdbc:postgresql://localhost:5432/chatp2p
   user=postgres
   password=<mật khẩu PostgreSQL của bạn>
   ```

   Có thể dùng `data/db.properties.example` làm mẫu. Biến môi trường `CHATP2P_DB_URL`, `CHATP2P_DB_USER` và `CHATP2P_DB_PASSWORD` ghi đè file cấu hình. Client trên máy khác chỉ cần IP và port của Chat Server, không cần kết nối PostgreSQL.

3. Server tự tạo các bảng `accounts` và `chat_history` khi khởi động. Nếu có `data/users.dat`, các tài khoản cũ được nhập vào PostgreSQL theo hash PBKDF2 hiện có. Cache SQLite trên máy server cũng được nhập. Các file cũ không bị xóa.

## Chạy ứng dụng

```powershell
.\scripts\build.ps1
.\scripts\run-server.ps1
.\scripts\run-client.ps1
```

Chế độ console:

```powershell
.\scripts\run-server.ps1 -Console -Port 5000
.\scripts\run-client.ps1 -Console -HostName 192.168.1.10 -Port 5000 `
    -ClientName client-a -Password <mật-khẩu-ứng-dụng>
```

Để hai máy chat trực tiếp, cho phép cổng TCP của server và cổng P2P động của client qua firewall. Script `scripts/allow-chat-p2p-firewall.ps1` tạo các quy tắc cần thiết trên Windows. UDP Broadcast tìm server LAN và UDP Multicast hỗ trợ nhóm.

## Lịch sử

Client lưu một cache SQLite tại `data/chat_history/chat.db`. Khi đăng nhập, client nhập lịch sử cũ từ cache lên PostgreSQL rồi tải lịch sử theo tài khoản về cache. Các sự kiện mới được đồng bộ nền. Bản ghi gồm chat riêng, chat nhóm và metadata file; nội dung file và media không được lưu trong database. Nếu client đóng trước khi đồng bộ xong, cache sẽ thử gửi lại ở lần đăng nhập tiếp theo. Để nhập cache cũ, hãy đăng nhập cùng địa chỉ và port server mà cache đã dùng.

Thông tin tài khoản và nội dung chat trong PostgreSQL do server quản lý. Mỗi client chỉ được tải lịch sử của tài khoản đã đăng nhập. Thông tin đường dẫn file cục bộ chỉ hữu ích trên máy đã nhận file; file cần được truyền lại nếu máy khác muốn có byte của file đó.

## Kiểm thử

```powershell
.\scripts\build.ps1 -RunTests
```

Kiểm thử dùng một PostgreSQL schema riêng, tạo và xóa trong database cấu hình. Nó kiểm tra đăng nhập, nhập tài khoản cũ, P2P chat/file, nhóm, và tải lịch sử lại bằng cache mới. Xem [kiến trúc](docs/architecture.md) để biết luồng dữ liệu.
