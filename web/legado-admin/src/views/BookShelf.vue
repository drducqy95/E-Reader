<template>
  <div class="h-full w-full flex flex-col md:flex-row transition-colors duration-300" :class="isNight ? 'bg-navy-dark text-gray-300' : 'bg-warm-paper text-gray-800'">
    <!-- Sidebar Navigation (collapsible on desktop) -->
    <transition name="sidebar-slide">
      <div 
        v-show="sidebarOpen"
        class="w-full md:w-64 flex flex-col py-6 md:py-12 px-6 shadow-md z-10 flex-shrink-0 overflow-hidden"
        :class="isNight ? 'bg-[#121620]' : 'bg-[#e9dec1]/30'"
      >
        <!-- Sidebar Header -->
        <div class="flex flex-col gap-4">
          <h1 class="text-2xl font-bold font-serif flex-shrink-0">Legado</h1>
          
          <!-- Search input -->
          <div class="w-full">
            <el-input
              placeholder="Tìm kiếm sách..."
              v-model="searchWord"
              class="w-full !rounded-full custom-search"
              :prefix-icon="SearchIcon"
              @keyup.enter="searchBook"
              ref="searchInputRef"
            >
            </el-input>
          </div>
        </div>
        
        <!-- Bottom Controls -->
        <div class="mt-6 md:mt-12 flex flex-col gap-6 md:gap-8 flex-grow">
          <div class="flex-1">
            <h3 class="text-xs font-semibold uppercase tracking-wider opacity-60 mb-2 md:mb-4">Gần đây</h3>
            <el-tag
              :type="readingRecent.name == 'Chưa có lịch sử đọc' ? 'info' : 'primary'"
              class="cursor-pointer max-w-full overflow-hidden text-ellipsis transition-transform hover:scale-105"
              size="large"
              @click="onClickRecent"
              :class="{ 'opacity-50 pointer-events-none': readingRecent.bookUrl == '' }"
            >
              {{ readingRecent.name }}
            </el-tag>
          </div>
          
          <div class="flex-1">
            <h3 class="text-xs font-semibold uppercase tracking-wider opacity-60 mb-2 md:mb-4">Cài đặt</h3>
            <el-tag
              :type="connectType"
              size="large"
              class="cursor-pointer max-w-full overflow-hidden text-ellipsis transition-transform hover:scale-105"
              :class="{ 'opacity-50 pointer-events-none': newConnect }"
              @click="setLegadoRetmoteUrl"
            >
              {{ connectStatus }}
            </el-tag>
          </div>
        </div>

        <!-- Footer Icons -->
        <div class="hidden md:flex items-center mt-auto pt-6 opacity-50 hover:opacity-100 transition-opacity">
          <a href="https://github.com/dat-bi/legado-qt" target="_blank" class="w-8 h-8 rounded-full bg-white/10 flex items-center justify-center p-1.5 hover:bg-white/20">
            <img :src="githubUrl" alt="GitHub" class="w-full h-full object-contain" />
          </a>
        </div>
      </div>
    </transition>

    <!-- Main Content Area -->
    <div class="flex-1 flex flex-col relative overflow-hidden" ref="shelfWrapper">
      <!-- Top Sticky Navbar (View Controls) -->
      <div class="sticky top-0 z-20 px-4 md:px-6 py-3 flex justify-between items-center gap-3 backdrop-blur-md" :class="isNight ? 'bg-navy-dark/80 border-white/5' : 'bg-warm-paper/80 border-black/5'">
        <!-- Left: sidebar toggle + title + search -->
        <div class="flex items-center gap-2 flex-shrink-0">
          <button 
            class="w-9 h-9 flex items-center justify-center rounded-lg hover:bg-black/10 transition-colors"
            @click="sidebarOpen = !sidebarOpen"
            :title="sidebarOpen ? 'Thu gọn' : 'Mở rộng'"
          >
            <!-- Hamburger / Close SVG -->
            <svg v-if="!sidebarOpen" class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M4 6h16M4 12h16M4 18h16" />
            </svg>
            <svg v-else class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M11 19l-7-7 7-7M18 19l-7-7 7-7" />
            </svg>
          </button>
          <h2 class="text-lg font-semibold font-serif hidden sm:block">Kệ sách</h2>

          <!-- Animated Search Pill (left-aligned, next to hamburger) -->
          <div class="nav-search-container" v-if="!sidebarOpen || miniShelf">
            <input 
              class="nav-search-toggle" 
              type="checkbox" 
              :checked="!navSearchOpen" 
              @change="navSearchOpen = !($event.target as HTMLInputElement).checked"
            />
            <div class="nav-search-box" :class="isNight ? 'dark' : ''">
              <div class="nav-search-icon">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" />
                </svg>
              </div>
              <input 
                class="nav-search-input" 
                placeholder="Tìm kiếm..." 
                type="text"
                v-model="searchWord"
                @keyup.enter="searchBook"
                ref="navSearchRef"
              />
            </div>
          </div>
        </div>
        
        <div class="flex gap-2 items-center flex-shrink-0">
          <!-- View Toggle -->
          <div class="flex bg-black/5 dark:bg-white/10 rounded-lg p-1">
            <button class="p-1.5 px-2.5 rounded-md transition-colors" :class="isGridView ? 'bg-white dark:bg-[#2A3143] shadow-sm' : 'opacity-60 hover:opacity-100'" @click="isGridView = true">
              <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 16 16"><rect x="1" y="1" width="6" height="6" rx="1"/><rect x="9" y="1" width="6" height="6" rx="1"/><rect x="1" y="9" width="6" height="6" rx="1"/><rect x="9" y="9" width="6" height="6" rx="1"/></svg>
            </button>
            <button class="p-1.5 px-2.5 rounded-md transition-colors" :class="!isGridView ? 'bg-white dark:bg-[#2A3143] shadow-sm' : 'opacity-60 hover:opacity-100'" @click="isGridView = false">
              <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 16 16"><rect x="1" y="1" width="14" height="3" rx="1"/><rect x="1" y="6.5" width="14" height="3" rx="1"/><rect x="1" y="12" width="14" height="3" rx="1"/></svg>
            </button>
          </div>
          
          <!-- Sort Dropdown -->
          <el-dropdown trigger="click" @command="handleSortCommand">
             <button class="p-1.5 px-3 bg-black/5 dark:bg-white/10 rounded-lg opacity-80 hover:opacity-100 transition-colors flex items-center gap-1.5">
               <span class="text-xs font-medium uppercase">{{ sortLabel }}</span>
               <svg class="w-3 h-3 opacity-60" fill="currentColor" viewBox="0 0 16 16"><path d="M3 5h10L8 11z"/></svg>
             </button>
             <template #dropdown>
               <el-dropdown-menu>
                 <el-dropdown-item command="recent" :class="{ 'is-active': sortMode === 'recent' }">Mới cập nhật</el-dropdown-item>
                 <el-dropdown-item command="reading" :class="{ 'is-active': sortMode === 'reading' }">Đang đọc</el-dropdown-item>
                 <el-dropdown-item command="name" :class="{ 'is-active': sortMode === 'name' }">Tên sách</el-dropdown-item>
               </el-dropdown-menu>
             </template>
          </el-dropdown>
        </div>
      </div>

      <!-- Bookshelf Grid -->
      <div class="flex-1 overflow-y-auto px-4 md:px-6 pb-12 custom-scrollbar">
        <!-- Empty State -->
        <div v-if="books.length === 0" class="w-full h-full flex flex-col items-center justify-center opacity-60">
           <svg class="w-32 h-32 mb-6 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" />
           </svg>
           <p class="text-lg font-medium font-serif">Kệ sách đang trống</p>
           <p class="text-sm mt-2 opacity-70">Tìm kiếm hoặc thêm sách để bắt đầu đọc.</p>
        </div>
        
        <book-items
          v-else
          :books="sortedBooks"
          @bookClick="handleBookClick"
          :isSearch="isSearching"
          :class="isGridView ? '' : 'flex flex-col gap-4'"
          :isGridView="isGridView"
        ></book-items>
      </div>
    </div>
    <TranslateToggle class="fixed bottom-6 right-6 z-50 shadow-2xl rounded-full" />
  </div>
</template>

<script setup lang="ts">
import '@/assets/bookshelf.css'
import '@/assets/fonts/shelffont.css'
import { useBookStore } from '@/store'
import TranslateToggle from '@/components/TranslateToggle.vue'
import githubUrl from '@/assets/imgs/github.png'
import { useLoading } from '@/hooks/loading'
import { Search as SearchIcon } from '@element-plus/icons-vue'
import { baseURL_localStorage_key } from '@/api/axios'
import API, {
  legado_http_entry_point,
  parseLeagdoHttpUrlWithDefault,
  setApiEntryPoint,
} from '@api'
import { validatorHttpUrl } from '@/utils/utils'
import type { Book, SeachBook } from '@/book'
import type { webReadConfig } from '@/web'

const store = useBookStore()
const isNight = computed(() => store.isNight)

/** shortcuts of `store.setConfig` */
const applyReadConfig = (config?: webReadConfig) => {
  try {
    if (config !== undefined) store.setConfig(config)
  } catch {
    ElMessage.info('Lỗi phân tích cấu hình giao diện đọc')
  }
}

const readingRecent = ref<typeof store.readingBook>({
  name: 'Chưa có lịch sử đọc',
  author: '',
  bookUrl: '',
  chapterIndex: 0,
  chapterPos: 0,
  isSeachBook: false,
})

const shelfWrapper = ref<HTMLElement>()
//const shelfWrapper = useTemplateRef<HTMLElement>("shelfWrapper")
const { showLoading, closeLoading, loadingWrapper, isLoading } = useLoading(
  shelfWrapper,
  'Đang lấy thông tin sách',
)

// 书架书籍和在线书籍搜索
const books = shallowRef<Book[] | SeachBook[]>([])
const shelf = computed(() => store.shelf)
const searchWord = ref('')
const isSearching = ref(false)
const isGridView = ref(true)
const miniShelf = computed(() => window.innerWidth < 768)
const sidebarOpen = ref(!miniShelf.value)
const searchInputRef = ref()
const navSearchRef = ref()
const navSearchOpen = ref(false)
const openNavSearch = () => {
  navSearchOpen.value = true
  nextTick(() => navSearchRef.value?.focus())
}
const sortMode = ref<'recent' | 'reading' | 'name'>('recent')
const sortLabel = computed(() => {
  switch (sortMode.value) {
    case 'recent': return 'Mới nhất'
    case 'reading': return 'Đang đọc'
    case 'name': return 'Tên'
  }
})
const handleSortCommand = (command: string) => {
  sortMode.value = command as typeof sortMode.value
}
const sortedBooks = computed(() => {
  const arr = [...books.value] as Book[]
  switch (sortMode.value) {
    case 'recent':
      return arr.sort((a, b) => (b.lastCheckTime || 0) - (a.lastCheckTime || 0))
    case 'reading':
      return arr.sort((a, b) => (b.durChapterTime || 0) - (a.durChapterTime || 0))
    case 'name':
      return arr.sort((a, b) => a.name.localeCompare(b.name))
    default:
      return arr
  }
})

watchEffect(() => {
  if (isSearching.value && searchWord.value != '') return
  isSearching.value = false
  books.value = []
  if (searchWord.value == '') {
    books.value = shelf.value
    return
  }
  books.value = shelf.value.filter(book => {
    return (
      book.name.includes(searchWord.value) ||
      book.author.includes(searchWord.value)
    )
  })
})

// 监听翻译模式变化
watch(
  () => store.isTranslateMode,
  () => {
    // 重新加载书架
    loadingWrapper(store.loadBookShelf())
    // If currently searching, re-trigger search with new translate mode
    if (isSearching.value && searchWord.value) {
      searchBook()
    }
  },
)

//搜索在线书籍
const searchBook = () => {
  if (searchWord.value == '') return
  books.value = []
  store.clearSearchBooks()
  showLoading()
  isSearching.value = true
  API.search(
    searchWord.value,
    searcBooks => {
      if (isLoading) {
        closeLoading()
      }
      try {
        store.setSearchBooks(searcBooks)
        books.value = store.searchBooks
        //store.searchBooks.forEach((item) => books.value.push(item));
      } catch (e) {
        ElMessage.error('Dữ liệu backend lỗi')
        throw e
      }
    },
    () => {
      closeLoading()
      if (books.value.length == 0) {
        ElMessage.info('Kết quả tìm kiếm trống')
      }
    },
    store.isTranslateMode,
  )
}

//连接状态
const connectionStore = useConnectionStore()
const { connectStatus, connectType, newConnect } = storeToRefs(connectionStore)

const setLegadoRetmoteUrl = () => {
  ElMessageBox.prompt(
    '请输入 后端地址 ( 如：http://127.0.0.1:9527 或者通过内网穿透的地址)',
    '提示',
    {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPlaceholder: legado_http_entry_point,
      inputValidator: value => validatorHttpUrl(value),
      inputErrorMessage: '输入的格式不对',
      beforeClose: (action, instance, done) => {
        if (action === 'confirm') {
          connectionStore.setNewConnect(true)
          instance.confirmButtonLoading = true
          instance.confirmButtonText = '校验中……'
          // instance.inputValue
          const url = new URL(instance.inputValue).toString()
          API.getReadConfig(url)
            .then(function (config) {
              connectionStore.setNewConnect(false)
              applyReadConfig(config)
              instance.confirmButtonLoading = false
              store.clearSearchBooks()
              setApiEntryPoint(...parseLeagdoHttpUrlWithDefault(url))
              if (url === location.origin) {
                localStorage.removeItem(baseURL_localStorage_key)
              } else {
                localStorage.setItem(baseURL_localStorage_key, url)
              }
              store.loadBookShelf()
              done()
            })
            .catch(function (error) {
              connectionStore.setNewConnect(false)
              instance.confirmButtonLoading = false
              instance.confirmButtonText = '确定'
              throw error
            })
        } else {
          done()
        }
      },
    },
  )
}

const router = useRouter()
const handleBookClick = async (book: SeachBook | Book) => {
  // 判断是否为 searchBook
  const isSeachBook = 'respondTime' in book
  if (isSeachBook) {
    await API.saveBook(book)
  }
  const {
    bookUrl,
    name,
    author,
    // @ts-expect-error: descruct with default value
    durChapterIndex = 0,
    // @ts-expect-error: descruct with default value
    durChapterPos = 0,
  } = book

  toDetail(bookUrl, name, author, durChapterIndex, durChapterPos, isSeachBook)
}
const toDetail = (
  bookUrl: string,
  bookName: string,
  bookAuthor: string,
  chapterIndex: number,
  chapterPos: number,
  isSeachBook: boolean | undefined = false,
  fromReadRecentClick = false,
) => {
  if (bookName === 'Chưa có lịch sử đọc') return
  // 最近书籍不再书架上 自动搜索
  if (
    fromReadRecentClick &&
    shelf.value.every(book => book.bookUrl !== bookUrl)
  ) {
    searchWord.value = bookName
    searchBook()
    return
  }
  sessionStorage.setItem('bookUrl', bookUrl)
  sessionStorage.setItem('bookName', bookName)
  sessionStorage.setItem('bookAuthor', bookAuthor)
  sessionStorage.setItem('chapterIndex', String(chapterIndex))
  sessionStorage.setItem('chapterPos', String(chapterPos))
  sessionStorage.setItem('isSeachBook', String(isSeachBook))
  readingRecent.value = {
    name: bookName,
    author: bookAuthor,
    bookUrl,
    chapterIndex,
    chapterPos,
    isSeachBook,
  }
  localStorage.setItem('readingRecent', JSON.stringify(readingRecent.value))
  router.push({
    path: '/chapter',
  })
}

const onClickRecent = () => {
  if (readingRecent.value.bookUrl) {
    toDetail(
      readingRecent.value.bookUrl,
      readingRecent.value.name,
      readingRecent.value.author,
      readingRecent.value.chapterIndex,
      readingRecent.value.chapterPos,
      readingRecent.value.isSeachBook,
      true,
    )
  }
}


const loadShelf = async () => {
  await store.loadWebConfig()
  await store.saveBookProgress()
  //确保各种网络情况下同步请求先完成
  await store.loadBookShelf()
}

onMounted(() => {
  //获取最近阅读书籍
  const readingRecentStr = localStorage.getItem('readingRecent')
  if (readingRecentStr != null) {
    readingRecent.value = JSON.parse(readingRecentStr)
    if (typeof readingRecent.value.chapterIndex == 'undefined') {
      readingRecent.value.chapterIndex = 0
    }
  }
  console.log('bookshelf mounted')
  loadingWrapper(loadShelf())
})
</script>

<style lang="scss">
/* Sidebar slide transition */
.sidebar-slide-enter-active,
.sidebar-slide-leave-active {
  transition: all 0.3s ease;
  max-width: 16rem;
}
.sidebar-slide-enter-from,
.sidebar-slide-leave-to {
  max-width: 0;
  padding-left: 0;
  padding-right: 0;
  opacity: 0;
}

/* Global overrides to scrollbars for bookshelf */
.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background-color: rgba(156, 163, 175, 0.5);
  border-radius: 9999px;
}
.custom-scrollbar::-webkit-scrollbar-track {
  background: transparent;
}

/* Animated Nav Search Pill */
.nav-search-container {
  position: relative;
  box-sizing: border-box;
  width: fit-content;
  margin-left: 4px;
}

.nav-search-box {
  box-sizing: border-box;
  position: relative;
  width: 260px;
  max-width: 50vw;
  height: 38px;
  display: flex;
  flex-direction: row-reverse;
  align-items: center;
  border-radius: 160px;
  background: rgba(0, 0, 0, 0.06);
  border: 1px solid rgba(0, 0, 0, 0.08);
  backdrop-filter: blur(8px);
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.nav-search-box.dark {
  background: rgba(255, 255, 255, 0.08);
  border-color: rgba(255, 255, 255, 0.1);
}

.nav-search-toggle:focus {
  border: none;
  outline: none;
}

.nav-search-toggle:checked ~ .nav-search-box {
  width: 38px;
  background: rgba(0, 0, 0, 0.04);
  border-color: rgba(0, 0, 0, 0.06);
}

.nav-search-toggle:checked ~ .nav-search-box.dark {
  background: rgba(255, 255, 255, 0.06);
  border-color: rgba(255, 255, 255, 0.08);
}

.nav-search-toggle:checked ~ .nav-search-box .nav-search-input {
  width: 0;
  padding: 0;
  opacity: 0;
}

.nav-search-toggle {
  box-sizing: border-box;
  width: 38px;
  height: 38px;
  position: absolute;
  left: 0;
  top: 0;
  z-index: 9;
  cursor: pointer;
  appearance: none;
  -webkit-appearance: none;
}

.nav-search-input {
  box-sizing: border-box;
  height: 100%;
  width: 210px;
  max-width: calc(50vw - 50px);
  background-color: transparent;
  border: none;
  outline: none;
  padding: 0 14px 0 10px;
  font-size: 0.85rem;
  color: inherit;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  font-family: inherit;
}

.nav-search-input::placeholder {
  color: rgba(0, 0, 0, 0.35);
}

.nav-search-box.dark .nav-search-input::placeholder {
  color: rgba(255, 255, 255, 0.4);
}

.nav-search-icon {
  box-sizing: border-box;
  width: 38px;
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.nav-search-icon svg {
  width: 16px;
  height: 16px;
  opacity: 0.5;
  transition: opacity 0.2s;
}

.nav-search-box:hover .nav-search-icon svg {
  opacity: 0.7;
}

/* Sidebar search override */
.custom-search .el-input__wrapper {
  background: rgba(0, 0, 0, 0.04) !important;
  border: 1px solid rgba(0, 0, 0, 0.06) !important;
  box-shadow: none !important;
  border-radius: 999px !important;
  backdrop-filter: blur(6px);
  transition: all 0.2s ease;
}

.custom-search .el-input__wrapper:hover,
.custom-search .el-input__wrapper:focus-within {
  background: rgba(0, 0, 0, 0.06) !important;
  border-color: rgba(0, 0, 0, 0.12) !important;
}
</style>
