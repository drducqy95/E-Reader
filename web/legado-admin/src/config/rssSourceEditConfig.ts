export default {
  base: {
    name: 'Cơ bản',
    children: [
      {
        title: 'Tên miền nguồn',
        id: 'sourceUrl',
        type: 'String',
        hint: 'Thường là trang chủ, ví dụ: https://www.qidian.com',
        required: true,
      },
      {
        title: 'Biểu tượng',
        id: 'sourceIcon',
        type: 'String',
        hint: 'Link ảnh biểu tượng',
      },
      {
        title: 'Tên nguồn',
        id: 'sourceName',
        type: 'String',
        hint: 'Hiển thị trong danh sách nguồn',
        required: true,
      },
      {
        title: 'Nhóm nguồn',
        id: 'sourceGroup',
        type: 'String',
        hint: 'Thông tin phân loại nguồn',
      },
      {
        title: 'Ghi chú',
        id: 'sourceComment',
        type: 'String',
        hint: 'Tác giả và trạng thái nguồn',
      },
      {
        title: 'URL Phân loại',
        id: 'sortUrl',
        type: 'String',
        hint: 'Tên1::Link1\nTên2::Link2',
      },
      {
        title: 'URL Đăng nhập',
        id: 'loginUrl',
        type: 'String',
        hint: 'Điền URL đăng nhập, chỉ dùng khi nguồn cần đăng nhập',
      },
      {
        title: 'Giao diện Đăng nhập',
        id: 'loginUi',
        type: 'String',
        hint: 'Giao diện đăng nhập tùy chỉnh',
      },
      {
        title: 'Kiểm tra Đăng nhập',
        id: 'loginCheckJs',
        type: 'String',
        hint: 'JS kiểm tra đăng nhập',
      },
      {
        title: 'Giải mã bìa',
        id: 'coverDecodeJs',
        type: 'String',
        hint: 'JS giải mã ảnh bìa',
      },
      {
        title: 'Header',
        id: 'header',
        type: 'String',
        hint: 'Nhận diện User-Agent',
      },
      {
        title: 'Biến tùy chỉnh',
        id: 'variableComment',
        type: 'String',
        hint: 'Mô tả biến nguồn',
      },
      {
        title: 'Tỷ lệ đồng thời',
        id: 'concurrentRate',
        type: 'String',
        hint: 'Tốc độ',
      },
      {
        title: 'Thư viện JS',
        id: 'jsLib',
        type: 'String',
        hint: 'Thư viện JS, có thể điền JS hoặc object key-value để tải online',
      },
    ],
  },
  list: {
    name: 'Danh sách',
    children: [
      {
        title: 'Quy tắc danh sách',
        id: 'ruleArticles',
        type: 'String',
        hint: 'Kết quả là List<Element>',
      },
      {
        title: 'Quy tắc trang sau',
        id: 'ruleNextPage',
        type: 'String',
        hint: 'Link trang sau, kết quả là List<String> hoặc String',
      },
      {
        title: 'Quy tắc tiêu đề',
        id: 'ruleTitle',
        type: 'String',
        hint: 'Tiêu đề bài viết, kết quả là String',
      },
      {
        title: 'Quy tắc thời gian',
        id: 'rulePubDate',
        type: 'String',
        hint: 'Thời gian đăng bài, kết quả là String',
      },
      {
        title: 'Quy tắc mô tả',
        id: 'ruleDescription',
        type: 'String',
        hint: 'Mô tả ngắn gọn, kết quả là String',
      },
      {
        title: 'Quy tắc hình ảnh',
        id: 'ruleImage',
        type: 'String',
        hint: 'Link ảnh bài viết, kết quả là String',
      },
      {
        title: 'Quy tắc liên kết',
        id: 'ruleLink',
        type: 'String',
        hint: 'Link bài viết, kết quả là String',
      },
    ],
  },
  webView: {
    name: 'WebView',
    children: [
      {
        title: 'Quy tắc nội dung',
        id: 'ruleContent',
        type: 'String',
        hint: 'Nội dung chính bài viết',
      },
      {
        title: 'Quy tắc Style',
        id: 'style',
        type: 'String',
        hint: 'Style bài viết, điền CSS',
      },
      {
        title: 'Quy tắc Inject',
        id: 'injectJs',
        type: 'String',
        hint: 'Inject JavaScript vào trang',
      },
      {
        title: 'Danh sách đen',
        id: 'contentBlacklist',
        type: 'String',
        hint: 'Chặn tải link WebView, phân cách bằng dấu phẩy',
      },
      {
        title: 'Danh sách trắng',
        id: 'contentWhitelist',
        type: 'String',
        hint: 'Cho phép tải link WebView, phân cách bằng dấu phẩy',
      },
      {
        title: 'Chặn Link',
        id: 'shouldOverrideUrlLoading',
        type: 'String',
        hint: 'JS chặn link, biến url là link hiện tại, trả về true để chặn',
      },
    ],
  },
  other: {
    name: 'Khác',
    children: [
      {
        title: 'Kiểu danh sách',
        id: 'articleStyle',
        type: 'Array',
        array: ['Mặc định', 'Ảnh lớn', 'Hai cột'],
      },
      {
        title: 'Tải với BaseUrl',
        id: 'loadWithBaseUrl',
        type: 'Boolean',
      },
      {
        title: 'Bật JS',
        id: 'enableJs',
        type: 'Boolean',
      },
      {
        title: 'Kích hoạt',
        id: 'enabled',
        type: 'Boolean',
      },
      {
        title: 'Cookie',
        id: 'enabledCookieJar',
        type: 'Boolean',
      },
      {
        title: 'Đơn URL',
        id: 'singleUrl',
        type: 'Boolean',
      },
      {
        title: 'Thứ tự',
        id: 'customOrder',
        type: 'Number',
      },
    ],
  },
}
