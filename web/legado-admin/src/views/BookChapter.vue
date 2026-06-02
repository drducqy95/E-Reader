<template>
  <div
    class="min-h-screen w-full relative transition-colors duration-500 overflow-x-hidden"
    :style="bodyTheme"
    @click="showToolBar = !showToolBar"
  >
    <!-- Top Progress Bar -->
    <div class="fixed top-0 left-0 h-1 bg-blue-500/20 w-full z-50">
       <div class="h-full bg-gradient-to-r from-blue-400 to-indigo-500 transition-all duration-500 pointer-events-none rounded-r-full" :style="{ width: readingProgressPercent }"></div>
    </div>

    <!-- ═══ TOP HEADER BAR ═══ -->
    <div 
      class="fixed top-0 left-0 right-0 z-40 transition-all duration-300"
      :class="showToolBar ? 'translate-y-0 opacity-100' : '-translate-y-full opacity-0'"
      @click.stop
    >
      <div 
        class="flex items-center justify-between px-4 py-3 backdrop-blur-xl border-b border-black/5 shadow-lg"
        :style="{ background: popupColor }"
      >
        <!-- Left: Back to shelf -->
        <div class="flex items-center gap-3">
          <button class="w-10 h-10 flex items-center justify-center rounded-full hover:bg-black/10 transition-colors" @click="toShelf">
            <span class="iconfont text-xl">&#58920;</span>
          </button>
          <div class="hidden sm:block max-w-[200px] md:max-w-[300px] truncate text-sm font-medium opacity-80">
            {{ catalog[chapterIndex]?.title || '' }}
          </div>
        </div>
        
        <!-- Center: Chapter info (mobile) -->
        <div class="sm:hidden text-sm font-medium opacity-80 truncate max-w-[160px]">
          {{ catalog[chapterIndex]?.title || '' }}
        </div>

        <!-- Right: Action buttons -->
        <div class="flex items-center gap-1">
          <el-popover
            placement="bottom-end"
            :width="popupWidth"
            trigger="click"
            :show-arrow="false"
            v-model:visible="popCataVisible"
            popper-class="pop-cata"
          >
            <PopCatalog @getContent="getContent" class="popup" />
            <template #reference>
              <button class="w-10 h-10 flex items-center justify-center rounded-full hover:bg-black/10 transition-colors" :class="{ 'opacity-50 pointer-events-none': false }">
                <span class="iconfont text-lg">&#58905;</span>
              </button>
            </template>
          </el-popover>
          
          <el-popover
            placement="bottom-end"
            :width="popupWidth"
            trigger="click"
            :show-arrow="false"
            v-model:visible="readSettingsVisible"
            popper-class="pop-setting"
          >
            <read-settings class="popup" />
            <template #reference>
              <button class="w-10 h-10 flex items-center justify-center rounded-full hover:bg-black/10 transition-colors" :class="{ 'opacity-50 pointer-events-none': noPoint }">
                <span class="iconfont text-lg">&#58971;</span>
              </button>
            </template>
          </el-popover>
          
          <button class="hidden md:flex w-10 h-10 items-center justify-center rounded-full hover:bg-black/10 transition-colors" :class="{ 'opacity-50 pointer-events-none': noPoint }" @click="toTop">
            <span class="iconfont text-lg">&#58914;</span>
          </button>
        </div>
      </div>
    </div>

    <!-- ═══ BOTTOM NAVIGATION BAR ═══ -->
    <div 
      class="fixed bottom-0 left-0 right-0 z-40 transition-all duration-300"
      :class="showToolBar ? 'translate-y-0 opacity-100' : 'translate-y-full opacity-0'"
      @click.stop
    >
      <div 
        class="flex items-center justify-between px-2 py-2 backdrop-blur-xl border-t border-black/5 shadow-[0_-4px_16px_rgba(0,0,0,0.08)]"
        :style="{ background: popupColor }"
      >
        <button
          class="flex-1 flex items-center justify-center gap-2 py-3 rounded-xl hover:bg-black/5 transition-colors"
          :class="{ 'opacity-40 pointer-events-none': noPoint }"
          @click="toPreChapter"
        >
          <span class="iconfont text-lg">&#58920;</span>
          <span class="text-sm font-medium">Chương trước</span>
        </button>
        
        <!-- Chapter indicator -->
        <div class="px-4 text-xs text-center opacity-60 min-w-[80px]">
          <div class="font-semibold">{{ chapterIndex + 1 }}/{{ catalog.length }}</div>
        </div>
        
        <button
          class="flex-1 flex items-center justify-center gap-2 py-3 rounded-xl hover:bg-black/5 transition-colors"
          :class="{ 'opacity-40 pointer-events-none': noPoint }"
          @click="toNextChapter"
        >
          <span class="text-sm font-medium">Chương sau</span>
          <span class="iconfont text-lg">&#58913;</span>
        </button>
      </div>
    </div>

    <!-- ═══ IMMERSIVE READING AREA ═══ -->
    <div class="mx-auto min-h-screen flex flex-col transition-all duration-500 ease-in-out px-5 md:px-10 pt-4 pb-20" ref="content" :style="chapterTheme">
      <div class="w-full text-lg md:text-xl leading-[1.9] text-justify tracking-wide">
        <div class="w-full h-16" ref="top"></div>
        <div
          v-for="data in chapterData"
          :key="data.index"
          :chapterIndex="data.index"
          ref="chapter"
          class="chapter-content-block animate-fade-in"
        >
          <chapter-content
            ref="chapterRef"
            :chapterIndex="data.index"
            :contents="data.content"
            :title="data.title"
            :spacing="store.config.spacing"
            :fontSize="fontSize"
            :fontFamily="fontFamily"
            @readedLengthChange="onReadedLengthChange"
            v-if="showContent"
          />
        </div>
        <div class="w-full h-20 flex items-center justify-center mt-8" ref="loading">
           <div class="w-2 h-2 rounded-full bg-current opacity-20 animate-ping"></div>
        </div>
        <div class="w-full h-16" ref="bottom"></div>
      </div>
    </div>
  </div>
  <TranslateToggle class="fixed bottom-20 right-4 z-50 shadow-2xl rounded-full" />
</template>

<script setup lang="ts">
import jump from '@/plugins/jump'
import settings from '@/config/themeConfig'
import API from '@api'
import TranslateToggle from '@/components/TranslateToggle.vue'
import { useLoading } from '@/hooks/loading'
import { useThrottleFn } from '@vueuse/shared'
import { isNullOrBlank } from '@/utils/utils'

const content = ref()
// loading spinner
const { isLoading, loadingWrapper } = useLoading(content, 'Đang lấy thông tin')
const store = useBookStore()

const {
  catalog,
  popCataVisible,
  readSettingsVisible,
  miniInterface,
  showContent,
  bookProgress,
  theme,
  isNight,
} = storeToRefs(store)

const chapterPos = computed({
  get: () => store.readingBook.chapterPos,
  set: value => (store.readingBook.chapterPos = value),
})
const chapterIndex = computed({
  get: () => store.readingBook.chapterIndex,
  set: value => (store.readingBook.chapterIndex = value),
})
const isSeachBook = computed({
  get: () => store.readingBook.isSeachBook,
  set: value => (store.readingBook.isSeachBook = value),
})

// 当前阅读书籍readingBook持久化
watch(
  () => store.readingBook,
  book => {
    // 保存localStorage
    // localStorage.setItem(book.bookUrl, JSON.stringify(book));
    // 最近阅读
    localStorage.setItem('readingRecent', JSON.stringify(book))
    //保存 sessionStorage
    sessionStorage.setItem('chapterIndex', book.chapterIndex.toString())
    sessionStorage.setItem('chapterPos', book.chapterPos.toString())
  },
  { deep: 1 },
)

// 监听翻译模式变化
watch(
  () => store.isTranslateMode,
  async () => {
    // 重新加载目录和当前章节内容
    await store.loadWebCatalog(store.readingBook)
    getContent(chapterIndex.value, true, chapterPos.value)
  },
)

// 无限滚动
const infiniteLoading = computed(() => store.config.infiniteLoading)
let scrollObserver: IntersectionObserver | null
const loading = ref()
watchEffect(() => {
  if (!infiniteLoading.value) {
    scrollObserver?.disconnect()
  } else {
    scrollObserver?.observe(loading.value)
  }
})
const loadMore = () => {
  const index = chapterData.value.slice(-1)[0].index
  if (catalog.value.length - 1 > index) {
    getContent(index + 1, false)
    store.saveBookProgress() // 保存的是上一章的进度，不是预载的本章进度
  }
}
// IntersectionObserver回调 底部加载
const onReachBottom = (entries: IntersectionObserverEntry[]) => {
  if (isLoading.value) return
  for (const { isIntersecting } of entries) {
    if (!isIntersecting) return
    loadMore()
  }
}

// 字体
const fontFamily = computed(() => {
  if (store.config.font >= 0) {
    return settings.fonts[store.config.font]
  }
  return store.config.customFontName
})
const fontSize = computed(() => {
  return store.config.fontSize + 'px'
})

// 主题部分
const bodyColor = computed(() => settings.themes[theme.value].body)
const chapterColor = computed(() => settings.themes[theme.value].content)
const popupColor = computed(() => settings.themes[theme.value].popup)

const readWidth = computed(() => {
  if (!miniInterface.value) {
    return store.config.readWidth - 130 + 'px'
  } else {
    return window.innerWidth + 'px'
  }
})
const popupWidth = computed(() => {
  if (!miniInterface.value) {
    return store.config.readWidth - 33
  } else {
    return window.innerWidth - 33
  }
})
const bodyTheme = computed(() => {
  return {
    background: bodyColor.value,
  }
})
const chapterTheme = computed(() => {
  return {
    background: chapterColor.value,
    width: readWidth.value,
  }
})
const showToolBar = ref(false)
const readingProgressPercent = computed(() => {
  if (catalog.value.length === 0) return '0%'
  return Math.round(((chapterIndex.value + 1) / catalog.value.length) * 100) + '%'
})
// leftBarTheme/rightBarTheme removed — replaced by top/bottom bar layout

/**
 * pc移动端判断 最大阅读宽度修正
 * 阅读宽度最小为640px 加上工具栏 68px 52px 取较大值 为 776px
 */
const onResize = () => {
  store.setMiniInterface(window.innerWidth < 776)
  const width = store.config.readWidth /**包含padding */
  checkPageWidth(width)
}
/** 判断阅读宽度是否超出页面或者低于默认值640 */
const checkPageWidth = (readWidth: number) => {
  if (store.miniInterface) return
  if (readWidth < 640) store.config.readWidth = 640
  if (readWidth + 2 * 68 > window.innerWidth) store.config.readWidth -= 160
}
watch(
  () => store.config.readWidth,
  width => checkPageWidth(width),
)
// 顶部底部跳转
const top = ref()
const bottom = ref()
const toTop = () => {
  jump(top.value)
}
const toBottom = () => {
  jump(bottom.value)
}

// 书架路由切换
const router = useRouter()
const toShelf = () => {
  router.push('/')
}

// 获取章节内容
const chapterData = ref<{ index: number; content: string[]; title: string }[]>(
  [],
)
const noPoint = ref(true)
const getContent = (index: number, reloadChapter = true, chapterPos = 0) => {
  if (reloadChapter) {
    //展示进度条
    store.setShowContent(false)
    //强制滚回顶层
    jump(top.value, { duration: 0 })
    //从目录，按钮切换章节时保存进度 预加载时不保存
    saveReadingBookProgressToBrowser(index, chapterPos)
    chapterData.value = []
  }
  const bookUrl = store.readingBook.bookUrl
  const { title, index: chapterIndex } = catalog.value[index]

  loadingWrapper(
    API.getBookContent(bookUrl, chapterIndex, store.isTranslateMode).then(
      res => {
        if (res.data.isSuccess) {
          const data = res.data.data
          const content = data.split(/\n+/)
          chapterData.value.push({ index, content, title })
          if (reloadChapter) toChapterPos(chapterPos)
        } else {
          ElMessage({ message: res.data.errorMsg, type: 'error' })
          const content = [res.data.errorMsg]
          chapterData.value.push({ index, content, title })
        }
        store.setContentLoading(true)
        noPoint.value = false
        store.setShowContent(true)
        if (!res.data.isSuccess) {
          throw res.data
        }
      },
      err => {
        const content = ['Lấy nội dung chương thất bại！']
        chapterData.value.push({ index, content, title })
        store.setShowContent(true)
        throw err
      },
    ),
  )
}

// 章节进度跳转和计算
const chapter = ref()
const chapterRef = ref()
const toChapterPos = (pos: number) => {
  nextTick(() => {
    if (chapterRef.value.length === 1)
      chapterRef.value[0].scrollToReadedLength(pos)
  })
}

// 60秒保存一次进度
const saveBookProgressThrottle = useThrottleFn(
  () => store.saveBookProgress(),
  60000,
)

const onReadedLengthChange = (index: number, pos: number) => {
  saveReadingBookProgressToBrowser(index, pos)
  saveBookProgressThrottle()
}

// 文档标题
watchEffect(() => {
  document.title = catalog.value[chapterIndex.value]?.title || document.title
})

// 阅读记录保存浏览器
const saveReadingBookProgressToBrowser = (index: number, pos: number) => {
  // 保存pinia
  chapterIndex.value = index
  chapterPos.value = pos
}

// 进度同步
// 返回导航变化 同步请求会在获取书架前完成

/**
 * VisibilityChange https://developer.mozilla.org/zh-CN/docs/Web/API/Document/visibilitychange_event
 * 监听关闭页面 切换tab 返回桌面 等操作
 * 注意不用监听点击链接导航变化 不对Safari<14.5兼容处理
 **/
const onVisibilityChange = () => {
  const _bookProgress = bookProgress.value
  if (document.visibilityState == 'hidden' && _bookProgress) {
    store.saveBookProgress()
  }
}
// 定时同步

// 章节切换
const toNextChapter = () => {
  store.setContentLoading(true)
  const index = chapterIndex.value + 1
  if (typeof catalog.value[index] !== 'undefined') {
    ElMessage({
      message: 'Chương sau',
      type: 'info',
    })
    getContent(index)
    store.saveBookProgress()
  } else {
    ElMessage({
      message: 'Đây là chương cuối',
      type: 'error',
    })
  }
}
const toPreChapter = () => {
  store.setContentLoading(true)
  const index = chapterIndex.value - 1
  if (typeof catalog.value[index] !== 'undefined') {
    ElMessage({
      message: 'Chương trước',
      type: 'info',
    })
    getContent(index)
    store.saveBookProgress()
  } else {
    ElMessage({
      message: 'Đây là chương đầu',
      type: 'error',
    })
  }
}

let canJump = true
// 监听方向键
const handleKeyPress = (event: KeyboardEvent) => {
  if (!canJump) return
  switch (event.key) {
    case 'ArrowLeft':
      event.stopPropagation()
      event.preventDefault()
      toPreChapter()
      break
    case 'ArrowRight':
      event.stopPropagation()
      event.preventDefault()
      toNextChapter()
      break
    case 'ArrowUp':
      event.stopPropagation()
      event.preventDefault()
      if (document.documentElement.scrollTop === 0) {
        ElMessage.warning('Đã đến đầu trang')
      } else {
        canJump = false
        jump(0 - document.documentElement.clientHeight + 100, {
          duration: store.config.jumpDuration,
          callback: () => (canJump = true),
        })
      }
      break
    case 'ArrowDown':
      event.stopPropagation()
      event.preventDefault()
      if (
        document.documentElement.clientHeight +
          document.documentElement.scrollTop ===
        document.documentElement.scrollHeight
      ) {
        ElMessage.warning('Đã đến cuối trang')
      } else {
        canJump = false
        jump(document.documentElement.clientHeight - 100, {
          duration: store.config.jumpDuration,
          callback: () => (canJump = true),
        })
      }
      break
  }
}

// 阻止默认滚动事件
const ignoreKeyPress = (event: KeyboardEvent) => {
  if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
    event.preventDefault()
    event.stopPropagation()
  }
}

onMounted(async () => {
  await store.loadWebConfig()
  //获取书籍数据
  const bookUrl = sessionStorage.getItem('bookUrl')
  const name = sessionStorage.getItem('bookName')
  const author = sessionStorage.getItem('bookAuthor')
  const chapterIndex = Number(sessionStorage.getItem('chapterIndex') || 0)
  const chapterPos = Number(sessionStorage.getItem('chapterPos') || 0)
  const isSeachBook = sessionStorage.getItem('isSeachBook') === 'true'
  if (isNullOrBlank(bookUrl) || isNullOrBlank(name) || author === null) {
    ElMessage.warning('Thông tin sách trống, sắp tự động về kệ sách...')
    return setTimeout(toShelf, 500)
  }
  const book: typeof store.readingBook = {
    // @ts-expect-error: bookUrl name author is NON_Blank string here
    bookUrl,
    // @ts-expect-error: bookUrl name author is NON_Blank string here
    name,
    author,
    chapterIndex,
    chapterPos,
    isSeachBook,
  }
  onResize()
  window.addEventListener('resize', onResize)
  loadingWrapper(
    store.loadWebCatalog(book).then(chapters => {
      store.setReadingBook(book)
      getContent(chapterIndex, true, chapterPos)
      window.addEventListener('keyup', handleKeyPress)
      window.addEventListener('keydown', ignoreKeyPress)
      // 兼容Safari < 14
      document.addEventListener('visibilitychange', onVisibilityChange)
      //监听底部加载
      scrollObserver = new IntersectionObserver(onReachBottom, {
        rootMargin: '-100% 0% 20% 0%',
      })
      if (infiniteLoading.value === true) scrollObserver.observe(loading.value)
      //第二次点击同一本书 页面标题不会变化
      document.title = '...'
      document.title = (name as string) + ' | ' + chapters[chapterIndex].title
    }),
  )
})

onUnmounted(() => {
  window.removeEventListener('keyup', handleKeyPress)
  window.removeEventListener('keydown', ignoreKeyPress)
  window.removeEventListener('resize', onResize)
  // 兼容Safari < 14
  document.removeEventListener('visibilitychange', onVisibilityChange)
  readSettingsVisible.value = false
  popCataVisible.value = false
  scrollObserver?.disconnect()
  scrollObserver = null
})

const addToBookShelfConfirm = async () => {
  const book = store.readingBook
  // 阅读的是搜索的书籍 并未在书架
  if (book.isSeachBook === true) {
    await ElMessageBox.confirm(`Có muốn thêm "${book.name}" vào kệ không?`, 'Thêm vào kệ', {
      confirmButtonText: 'Xác nhận',
      cancelButtonText: 'Không',
      type: 'info',
      /*
        ElMessageBox.confirm默认在触发hashChange事件时自动关闭
        按下物理返回键时触发hashChange事件
        使用router.push("/")则不会触发hashChange事件
        */
      closeOnHashChange: false,
    })
      .then(() => {
        //选择是，无动作
        isSeachBook.value = false
      })
      .catch(async () => {
        //选择否，删除书籍
        await API.deleteBook(book)
      })
      .finally(() => sessionStorage.removeItem('isSeachBook'))
  }
}
onBeforeRouteLeave(async (to, from, next) => {
  console.log('onBeforeRouteLeave')
  // 弹窗时停止响应按键翻页
  window.removeEventListener('keyup', handleKeyPress)
  await addToBookShelfConfirm()
  next()
})
</script>

<style lang="scss">
/* Base font styles for Legado Web */
@font-face {
  font-family: 'iconfont';
  src: url('@/assets/fonts/iconfont.woff?t=1690025732296') format('woff');
}
.iconfont {
  font-family: "iconfont" !important;
  font-size: 16px;
  font-style: normal;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

.chapter-content-block {
  margin-bottom: 2em;
}

/* Theme presets logic */
[style*="background: #FFF9F0"] {
  @apply text-[#2C2C2C];
}
[style*="background: #F4ECD8"] {
  @apply text-[#5C4A1E];
}
[style*="background: #1A1A2E"] {
  @apply text-[#C8C8D0];
}
[style*="background: #1B2A1B"] {
  @apply text-[#A8D5A2];
}
[style*="background: #0A1628"] {
  @apply text-[#7EB3D4];
}
</style>
