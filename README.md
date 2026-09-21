# ChatP2P - Phase 1

Đây là nền móng của đồ án chat theo kiến trúc **hybrid Client-Server + P2P**.
Phase 1 chỉ triển khai kết nối điều khiển TCP giữa client và central server, bắt tay
`HELLO/HELLO_ACK`, kiểm tra sống `PING/PONG`, ngắt kết nối có kiểm soát và logging.

> Chưa có đăng ký, đăng nhập, chat, peer discovery hoặc truyền file. Các chức năng
> đó thuộc các phase sau và không được giả lập trong mã nguồn Phase 1.

## 1. Quyết định kiến trúc

Project dùng layered architecture, tách theo package:

```text
ChatP2P/
|-- pom.xml
|-- scripts/
|   |-- build.ps1
|   |-- run-server.ps1
|   `-- run-client.ps1
|-- src/
|   |-- main/java/chatp2p/
|   |   |-- server/
|   |   |-- client/
|   |   |-- protocol/
|   |   `-- view/
|   `-- test/java/chatp2p/
|-- data/
|   |-- chat_history/
|   |-- received_files/
|   `-- logs/
`-- docs/
    `-- architecture.md
```

Cấu trúc nguồn được điều chỉnh sang convention Maven (`src/main/java`) thay vì
đặt file Java trực tiếp dưới thư mục gốc. Tên package và trách nhiệm lớp vẫn bám
yêu cầu; cách này giúp Java IDE, `javac` và Maven build nhất quán. Dữ liệu runtime
nằm trong `data/`; server log được ghi tại `data/logs/server.log`.

## 2. Yêu cầu môi trường

- JDK 17 trở lên (cần cả `java` và `javac` trong `PATH`).
- PowerShell 5.1 trở lên để dùng các script đi kèm.
- Maven là tùy chọn, project không dùng thư viện ngoài.

## 3. Build và chạy

Build bằng JDK thuần:

```powershell
.\scripts\build.ps1
```

Chạy dashboard Swing của server:

```powershell
.\scripts\run-server.ps1
```

Trên dashboard, nhập port rồi nhấn `Khởi động Server`. Dashboard hiển thị số
kết nối, danh sách socket/client và log sự kiện theo thời gian thực.

Chạy server ở chế độ console khi cần:

```powershell
.\scripts\run-server.ps1 -Console -Port 5000
```

Chạy giao diện Swing client ở terminal khác:

```powershell
.\scripts\run-client.ps1
```

Nhập Server Host, Server Port và tên client trên cửa sổ kết nối. Sau khi kết nối,
nút `Kiểm tra Ping` dùng để đo round-trip time đến server.

Chế độ console vẫn được giữ để kiểm tra nhanh hoặc chạy trên máy không có GUI:

```powershell
.\scripts\run-client.ps1 -Console -HostName localhost -Port 5000 -ClientName client-a
```

Build bằng Maven nếu đã cài Maven:

```powershell
mvn test
```

## 4. Kiểm thử Phase 1

Smoke test tự khởi động server trên một cổng trống, kết nối client, thực hiện
handshake và `PING/PONG`, sau đó đóng sạch tài nguyên:

```powershell
.\scripts\build.ps1 -RunTests
```

Kiểm thử thủ công:

1. Chạy server và thấy log `Server started`.
2. Chạy ít nhất hai client ở hai terminal khác nhau.
3. Mỗi client nhận `HELLO_ACK` và có thể chạy `ping`.
4. Gõ `quit`; server phải ghi nhận client disconnect mà không dừng server.
5. Đóng đột ngột một client; server vẫn tiếp tục nhận client khác.

Chi tiết luồng, protocol và diagram nằm trong [docs/architecture.md](docs/architecture.md).
