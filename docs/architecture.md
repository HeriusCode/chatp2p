# Phân tích và thiết kế kiến trúc

## Phạm vi hiện tại

Ứng dụng đã có control channel TCP, xác thực tài khoản, danh sách online, peer
discovery, chat/file/Audio/Video P2P và chat nhóm. Server xử lý đồng thời nhiều client bằng
thread pool; client có luồng đọc mạng riêng, logging và shutdown an toàn.

## Phân chia trách nhiệm toàn hệ thống

- **Central Server:** xác thực và quản lý user, trạng thái online, peer discovery,
  membership/chat nhóm, signaling và logging. Server không mang chat riêng hoặc
  dữ liệu file khi P2P hoạt động.
- **Client:** GUI, kết nối điều khiển tới server, lịch sử chat cục bộ, peer listener,
  kết nối trực tiếp và quản lý truyền file.
- **P2P channel:** TCP socket trực tiếp giữa hai client cho chat/file/media. Binary file
  được stream theo chunk; không chuyển file thành String và không chạy trên EDT.
- **Audio:** Java Sound thu WAV PCM mono 16 kHz; file WAV đi qua P2P channel và
  được phát/dừng ngay trong client bằng `Clip`.
- **Video:** file MP4/MOV/AVI/MKV/WEBM đi qua P2P channel và được mở bằng trình
  phát mặc định. Chưa có gọi video thời gian thực do Java SE không có webcam/codec.
- **UDP Broadcast:** client phát discovery trên cổng `5050`; server trả lời endpoint TCP.
- **UDP Multicast:** group `239.255.42.99:5051` phát chat nhóm trong LAN. Server
  vẫn xác thực thành viên và phân phối fallback; UUID ngăn hiển thị trùng.

```text
ClientMain -> ChatClient -> ServerConnection == TCP == ChatServer -> ClientHandler
                                  |                              |
                                  +-------- Protocol -----------+
```

## Luồng Client-Server

```text
Client                     Central Server
  |                              |
  |--- TCP connect ------------->|
  |--- LOGIN(user,password,port)>|
  |<-- LOGIN_ACK ----------------|
  |--- PING -------------------->|
  |<-- PONG ---------------------|
  |--- DISCONNECT ------------->|
  |--- TCP close -------------->|
```

Cùng control channel mang `REGISTER`, `LOGIN`, `GET_USERS`, `CONNECT_REQUEST`,
`JOIN_GROUP`, `LEAVE_GROUP`, `GROUP_CHAT` và các event trạng thái. Mỗi client có
một socket riêng và một `ClientHandler` riêng trong thread pool.

## Luồng P2P dự kiến (Phase 4-6)

```text
Client A                 Server                  Client B
   |-- CONNECT_REQUEST(B) ->|                        |
   |<- PEER_INFO(B, ip,port)|                        |
   |================ direct TCP connect ===========>|
   |---------------- FILE_REQUEST ----------------->|
   |<--------------- FILE_ACCEPT ------------------|
   |================ binary chunks ===============>|
   |---------------- FILE_END(checksum) ---------->|
```

Server chỉ làm discovery/signaling. Relay server là extension point khi direct
TCP thất bại, không thuộc phiên bản đầu.

## Protocol

Không dùng delimiter text vì ký tự phân cách có thể xuất hiện trong message và
khó giới hạn kích thước. Mỗi frame Phase 1 có dạng:

```text
+-----------+---------+------+----------------+-------------+----------------+
| magic:int | ver:u16 | type | correlation-id | field-count | key/value ...  |
+-----------+---------+------+----------------+-------------+----------------+
```

- `magic`: `0x43503250` (`CP2P`), phát hiện sai protocol sớm.
- `version`: hiện tại là `1`; hai đầu từ chối version không tương thích.
- `type`: mã số ổn định, không phụ thuộc tên enum Java.
- `correlation-id`: UUID ghép response với request tương ứng.
- `field-count`: số cặp key/value UTF-8; mỗi chuỗi có `length:int` ở trước.
- Giới hạn: tối đa 64 fields, key 128 bytes, value 64 KiB và frame 256 KiB.

Các nhóm message chính:

```text
REGISTER    username, password
LOGIN       username, password, peerPort
LOGIN_ACK   message, remoteAddress
PING        sentAt
PONG        sentAt, serverTime
JOIN_GROUP  groupName
LEAVE_GROUP groupName
GROUP_CHAT  messageId, groupName, sender, content, timestamp
DISCONNECT  reason
ERROR       code, message
```
File bytes sẽ đi trên P2P data channel bằng một header có độ dài rồi byte stream;
không nhét binary vào payload UTF-8 của control frame.

## Class diagram dạng text

```text
ServerMain
  `-- creates --> ChatServer
                    |-- owns --> ServerSocket
                    |-- owns --> ExecutorService
                    `-- submits --> ClientHandler (one per socket)
                                      `-- uses --> Protocol

ClientMain
  `-- uses --> ChatClient
                 |-- owns --> ServerConnection
                 |             |-- owns --> Socket
                 |             |-- owns --> reader ExecutorService
                 |             `-- uses --> Protocol
                 `-- owns --> ConnectionState

Protocol
  |-- reads/writes --> ProtocolMessage
  `-- validates ----> MessageType
```

## Sequence diagram Phase 1

```text
ClientMain   ChatClient   ServerConnection   ChatServer   ClientHandler
    |            |               |               |             |
    |--connect-->|--open socket-->|--- TCP ------>|--submit---->|
    |            |--LOGIN-------->|------------->|------------>|
    |            |                |              |  validate   |
    |            |<--future-------|<-------------|<--ACK-------|
    |<--ready----|                |              |             |
    |--ping----->|--PING--------->|------------->|------------>|
    |<--RTT------|<--PONG---------|<-------------|<------------|
```

## Multithreading và trạng thái

- Accept loop chạy ở thread gọi `ChatServer.start()`.
- Mỗi socket được xử lý bởi một task `ClientHandler` trong bounded fixed pool.
- Client có daemon reader thread duy nhất; không có hai thread cùng đọc một stream.
- Các request đang chờ được lưu bằng `ConcurrentHashMap<UUID, CompletableFuture>`.
- Ghi frame được khóa để byte của hai message không xen kẽ.
- Socket read timeout phát hiện peer im lặng; TCP keepalive cũng được bật.
- Shutdown đóng `ServerSocket` trước để giải phóng `accept()`, sau đó dừng pool.

## Hướng phát triển

Các package `model`, `view`, peer/file classes và thư mục dữ liệu nghiệp vụ sẽ
được tạo ở phase sở hữu chức năng đó. Không tạo class rỗng/TODO chỉ để đủ tên,
vì chúng tạo cảm giác project đã có chức năng trong khi chưa có implementation.

