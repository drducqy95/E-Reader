<template>
  <div 
    class="translate-toggle" 
    @click="toggleTranslate" 
    :class="{ active: isTranslateMode }"
    :title="isTranslateMode ? 'Đang dịch → Bấm để tắt' : 'Đang gốc → Bấm để dịch'"
  >
    <span class="lang-label">{{ isTranslateMode ? 'VN' : 'CN' }}</span>
  </div>
</template>

<script setup lang="ts">
import { useBookStore } from '@/store'

const store = useBookStore()
const isTranslateMode = computed(() => store.isTranslateMode)

const toggleTranslate = () => {
  store.toggleTranslateMode()
}
</script>

<style scoped>
.translate-toggle {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.06);
  backdrop-filter: blur(8px);
  color: #5c4a1e;
  display: flex;
  justify-content: center;
  align-items: center;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  transition: all 0.25s ease;
  user-select: none;
  border: 1px solid rgba(0, 0, 0, 0.06);
}

.translate-toggle.active {
  background: #11998e;
  color: white;
  border-color: transparent;
  box-shadow: 0 2px 10px rgba(17, 153, 142, 0.3);
}

.translate-toggle:hover {
  transform: scale(1.1);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
}

.translate-toggle:active {
  transform: scale(0.95);
}

.lang-label {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.3px;
  font-family: system-ui, -apple-system, sans-serif;
}
</style>

