# Giao diện Web Legado

Sử dụng Vue3 cho kệ sách web và chỉnh sửa nguồn web.

## Định tuyến (Routes)
* http://localhost:8080/ Kệ sách
* http://localhost:8080/#/bookSource Chỉnh sửa nguồn truyện
* http://localhost:8080/#/rssSource Chỉnh sửa nguồn RSS

## Hướng dẫn Phát triển (Development)

> **Lưu ý:** Cần có ứng dụng Legado cung cấp dịch vụ backend để hoạt động đầy đủ.

### Cài đặt thư viện
Bạn cần cài đặt Node.js trước. Sau đó mở terminal tại thư mục này và chạy lệnh:

```bash
npm install
```
(Hoặc `pnpm install` nếu bạn sử dụng pnpm)

### Chạy thử nghiệm (Test)
Để khởi động server phát triển và test ứng dụng:

```bash
npm run dev
```

Sau khi server chạy, truy cập vào http://localhost:8080/ trên trình duyệt để xem kết quả.

## Tương thích Trình duyệt

| ![IE](https://cdn.jsdelivr.net/npm/@browser-logos/edge/edge_32x32.png) | ![Firefox](https://cdn.jsdelivr.net/npm/@browser-logos/firefox/firefox_32x32.png) | ![Chrome](https://cdn.jsdelivr.net/npm/@browser-logos/chrome/chrome_32x32.png) | ![Safari](https://cdn.jsdelivr.net/npm/@browser-logos/safari/safari_32x32.png) |
| ---------------------------------------------------------------------- | --------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ | ------------------------------------------------------------------------------ |
| Edge ≥ 85                                                              | Firefox ≥ 79                                                                      | Chrome ≥ 85                                                                    | Safari ≥ 14.1                                                                    |
