<template>
  <el-input
    id="source-json"
    v-model="sourceString"
    type="textarea"
    placeholder="Dữ liệu JSON được xuất ở đây, có thể nhập trực tiếp vào app 'Legado'"
    :rows="30"
    @change="update"
    style="margin-bottom: 4px"
  ></el-input>
</template>
<script setup lang="ts">
import { useSourceStore } from '@/store'

const store = useSourceStore()
const sourceString = ref('')
const update = async (string: string) => {
  try {
    store.changeEditTabSource(JSON.parse(string))
  } catch {
    ElMessage({
      message: 'Định dạng nguồn dán vào bị lỗi',
      type: 'error',
    })
  }
}

watchEffect(async () => {
  const source = store.editTabSource
  if (Object.keys(source).length > 0) {
    sourceString.value = JSON.stringify(source, null, 4)
  } else {
    sourceString.value = ''
  }
})
</script>
<style lang="scss" scoped>
:deep(.el-input) {
  width: 100%;
}
:deep(#source-json) {
  height: calc(100vh - 50px);
}
</style>
