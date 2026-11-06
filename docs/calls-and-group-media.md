# Media nhóm và cuộc gọi P2P

## Media trong nhóm

Trong tab **Nhóm**, chọn nhóm đã tham gia rồi dùng các nút đính kèm, micro hoặc camera. File, bản ghi WAV, ảnh và video đều được truyền bằng TCP trực tiếp từ máy gửi tới từng thành viên đang online. Server chỉ cung cấp danh sách endpoint của những thành viên được phép nhận. Người offline không nhận lại byte file khi đăng nhập; PostgreSQL lưu metadata và SQLite lưu đường dẫn file cục bộ. Ảnh và video dùng lại phần xem trước trong hội thoại. Người nhận có thể tải bản sao từ file đã lưu trên máy mình.

## Gọi thoại và gọi video

Trong chat đơn, chọn người đang online rồi nhấn **Gọi thoại** hoặc **Gọi video** ở góc phải tiêu đề. Người nhận có thể nhận hoặc từ chối; cả hai bên có thể tắt micro và kết thúc. Cửa sổ cuộc gọi mở riêng nên vẫn xem được chat. Tín hiệu mời gọi và các khung âm thanh PCM / hình JPEG đi qua socket P2P tới cổng peer của máy nhận; server không chuyển tiếp media. Mỗi client chỉ giữ một cuộc gọi tại một thời điểm.

Muốn gọi giữa hai máy, cả hai cần chạy phiên bản client mới, đăng nhập cùng server, cùng mạng có thể đi tới IP peer, và cho phép cổng peer qua firewall. Micro, loa và camera phải sẵn sàng trên máy tương ứng. Bài kiểm thử tự động xác nhận lời mời, nhận, từ chối, kết thúc và truyền file nhóm; chất lượng hình/tiếng cần kiểm tra bằng hai máy có thiết bị thật.
