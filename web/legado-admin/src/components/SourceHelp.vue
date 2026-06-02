<script setup lang="ts">
import { Link } from '@element-plus/icons-vue'
</script>
<template>
  <el-link :icon="Link" href="/help/#appHelp" target="_blank"
    >Tài liệu trợ giúp App</el-link
  ><br />
  <el-link :icon="Link" href="/help/#ruleHelp" target="_blank"
    >Hướng dẫn tạo nguồn truyện</el-link
  ><br />
  <el-link :icon="Link" href="/help/#jsHelp" target="_blank"
    >Biến và hàm JS</el-link
  ><br />
  <el-link :icon="Link" href="/help/#xpathHelp" target="_blank"
    >Hướng dẫn cú pháp XPath</el-link
  ><br />
  <el-link :icon="Link" href="/help/#regexHelp" target="_blank"
    >Hướng dẫn Regex</el-link
  ><br />
  <el-link :icon="Link" href="/help/#txtTocRuleHelp" target="_blank"
    >Hướng dẫn Regex mục lục TXT</el-link
  ><br />
  <el-link :icon="Link" href="/help/#debugHelp" target="_blank"
    >Hướng dẫn debug nguồn</el-link
  ><br />
  <el-link :icon="Link" href="/help/#httpTTSHelp" target="_blank"
    >Quy tắc đọc online</el-link
  ><br />
  <el-link :icon="Link" href="/help/#webDavBookHelp" target="_blank">
    Hướng dẫn sử dụng WebDav</el-link
  ><br />
  <el-link :icon="Link" href="/help/#webDavHelp" target="_blank">
    Hướng dẫn sao lưu WebDav</el-link
  ><br />
  <el-link :icon="Link" href="https://regexr-cn.com/" target="_blank"
    >Công cụ kiểm tra Regex online</el-link
  ><br />
  <div style="margin-top: 20px">
    <span
      ><el-text
        ><code>^$()[]{}.?+*|</code> Đây là các ký tự đặc biệt trong Java Regex, cần escape khi khớp</el-text
      ></span
    ><br />
    <span
      ><el-text><code>(?s)</code> Tiền tố biểu thị phân tích nhiều dòng</el-text></span
    ><br />
    <span
      ><el-text><code>(?m)</code> Tiền tố biểu thị khớp từng dòng</el-text></span
    ><br />
    <span
      ><el-text><code>(?i)</code> Tiền tố biểu thị không phân biệt hoa thường</el-text></span
    ><br />
  </div>
</template>

<style lang="scss" scoped>
.el-link {
  padding: 4px;
}
.el-text {
  padding-top: 20px;
}
</style>
