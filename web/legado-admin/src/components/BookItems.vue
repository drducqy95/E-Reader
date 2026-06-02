<template>
  <div class="h-full w-full">
    <!-- View Switcher -->
    <div :class="isGridView ? 'grid grid-cols-2 sm:grid-cols-3 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-5 p-4' : 'flex flex-col gap-3 p-4'">
      <div
        v-for="book in books"
        :key="book.bookUrl"
        @click="handleClick(book)"
        class="group bg-white dark:bg-[#1A1F2E] rounded-xl overflow-hidden cursor-pointer shadow-sm hover:shadow-xl transition-all duration-300"
        :class="isGridView ? 'flex flex-col hover:[transform:perspective(1000px)_rotateY(-3deg)_translateY(-4px)]' : 'flex flex-row items-stretch hover:-translate-y-0.5'"
      >
        <!-- Cover Area -->
        <div 
          class="relative overflow-hidden bg-gray-100 dark:bg-gray-800 flex-shrink-0"
          :class="isGridView ? 'aspect-[3/4] w-full' : 'w-20 h-28'"
        >
          <img
            class="w-full h-full object-cover transition-transform duration-500 group-hover:scale-105"
            :src="getCover(book)"
            :key="book.coverUrl"
            @error.once="proxyImage"
            alt=""
            loading="lazy"
          />
          
          <!-- Hover Overlay (grid only) -->
          <div v-if="isGridView" class="absolute inset-0 bg-gradient-to-t from-black/80 via-black/40 to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-300 flex flex-col justify-end p-3 pointer-events-none">
            <p v-if="!isSearch && book.intro" class="text-white/90 text-[11px] leading-relaxed line-clamp-3 mb-1.5">
              {{ book.intro?.replace(/\s+/g, ' ').substring(0, 120) }}
            </p>
            <span v-if="!isSearch" class="text-white/70 text-[10px] line-clamp-1">
              📖 {{ book.latestChapterTitle }}
            </span>
          </div>

          <!-- Top-right badges (grid only) -->
          <div v-if="!isSearch && isGridView" class="absolute top-2 right-2 flex flex-col gap-1 items-end">
            <!-- Reading progress badge -->
            <span 
              class="px-2 py-0.5 rounded-full text-white text-[10px] font-bold shadow-md"
              :class="(book as Book).durChapterIndex > 0 ? 'bg-emerald-500' : 'bg-blue-500'"
            >
               {{ (book as Book).durChapterIndex > 0 
                  ? Math.round(((book as Book).durChapterIndex / ((book as Book).totalChapterNum || 1)) * 100) + '%' 
                  : 'Mới' }}
            </span>
            <!-- New chapters badge -->
            <span 
              v-if="(book as Book).lastCheckCount > 0"
              class="px-2 py-0.5 rounded-full bg-red-500 text-white text-[10px] font-bold shadow-md animate-pulse"
            >
              +{{ (book as Book).lastCheckCount }}
            </span>
          </div>

          <!-- Source label (grid, bottom-left) -->
          <div v-if="!isSearch && isGridView && (book as Book).originName" class="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/50 to-transparent px-2 py-1.5 group-hover:opacity-0 transition-opacity">
            <span class="text-white/80 text-[9px] font-medium line-clamp-1">{{ (book as Book).originName }}</span>
          </div>
        </div>

        <!-- Info Area -->
        <div class="flex flex-col p-3 flex-grow justify-between overflow-hidden" :class="isGridView ? '' : 'py-2'">
          <div>
            <h3 class="font-bold text-gray-900 dark:text-gray-100 leading-tight group-hover:text-blue-500 transition-colors"
                :class="isGridView ? 'text-sm line-clamp-2 mb-0.5' : 'text-sm line-clamp-1'">
              {{ book.name }}
            </h3>
            <p class="text-xs text-gray-500 dark:text-gray-400 font-medium opacity-80 line-clamp-1">
              {{ book.author }}
            </p>

            <!-- Grid: word count + kind tags -->
            <div v-if="isGridView && !isSearch" class="flex flex-wrap items-center gap-1 mt-1.5">
              <span v-if="book.wordCount" class="text-[10px] text-gray-400 bg-gray-100 dark:bg-gray-800 px-1.5 py-0.5 rounded">
                {{ book.wordCount }}
              </span>
              <span v-for="tag in getKindTags(book)" :key="tag" class="text-[10px] text-blue-500/80 bg-blue-50 dark:bg-blue-900/30 dark:text-blue-300/80 px-1.5 py-0.5 rounded">
                {{ tag }}
              </span>
            </div>

            <!-- List view: current chapter + source + word count -->
            <template v-if="!isGridView && !isSearch">
              <p class="text-[11px] text-gray-400 line-clamp-1 mt-0.5">
                {{ (book as Book).durChapterTitle || 'Chưa đọc' }}
              </p>
              <div class="flex items-center gap-2 mt-1">
                <span v-if="(book as Book).originName" class="text-[10px] text-purple-500/70 bg-purple-50 dark:bg-purple-900/20 dark:text-purple-300/70 px-1.5 py-0.5 rounded line-clamp-1">
                  {{ (book as Book).originName }}
                </span>
                <span v-if="book.wordCount" class="text-[10px] text-gray-400">
                  {{ book.wordCount }}
                </span>
                <span v-if="(book as Book).lastCheckCount > 0" class="text-[10px] text-red-500 font-bold">
                  +{{ (book as Book).lastCheckCount }} mới
                </span>
              </div>
            </template>
          </div>
          
          <div class="flex justify-between items-end" :class="isGridView ? 'mt-1.5' : 'mt-1'">
            <!-- Search mode tags -->
            <div v-if="isSearch" class="flex flex-wrap gap-1">
              <el-tag
                v-for="tag in book.kind?.split(',').slice(0, 2)"
                :key="tag"
                size="small"
                effect="plain"
                class="!border-none !bg-gray-100 dark:!bg-gray-800 dark:!text-gray-300"
              >
                {{ tag }}
              </el-tag>
            </div>
            
            <!-- Library mode info -->
            <div v-if="!isSearch" class="w-full">
               <div class="text-[11px] text-gray-400 flex justify-between w-full">
                 <span>{{ (book as Book).totalChapterNum }} chương</span>
                 <span>{{ dateFormat((book as Book).lastCheckTime) }}</span>
               </div>
               <!-- Progress Bar -->
               <div class="w-full bg-gray-200 dark:bg-gray-700 h-1 mt-1.5 rounded-full overflow-hidden">
                  <div class="bg-blue-500 h-full rounded-full transition-all duration-300" :style="`width: ${Math.min(100, Math.max(0, Math.round((((book as Book).durChapterIndex || 0) / ((book as Book).totalChapterNum || 1)) * 100)))}%`"></div>
               </div>
            </div>
          </div>
          
          <!-- Intro preview for search -->
          <p v-if="isSearch" class="mt-2 text-xs text-gray-600 dark:text-gray-400 line-clamp-2 leading-relaxed">
            {{ book.intro }}
          </p>
        </div>
      </div>
    </div>
  </div>
</template>
<script setup lang="ts">
import type { Book, SeachBook } from '@/book'
import { dateFormat, isLegadoUrl } from '../utils/utils'
import API from '@api'
const props = withDefaults(defineProps<{
  books: Array<Book | SeachBook>
  isSearch: boolean
  isGridView?: boolean
}>(), {
  isGridView: true
})

const emit = defineEmits(['bookClick'])
const handleClick = (book: Book | SeachBook) => emit('bookClick', book)
const getCover = ({ bookUrl, coverUrl }: Book | SeachBook) => {
  if (coverUrl === undefined) return API.getProxyCoverUrl(bookUrl)
  return isLegadoUrl(coverUrl) ? API.getProxyCoverUrl(coverUrl) : coverUrl
}
const proxyImage = (evt: Event) => {
  const target = evt.target as HTMLImageElement
  target.src = API.getProxyCoverUrl(target.src)
}

// Extract meaningful tags from kind field, filtering out dates, VIP, etc.
const getKindTags = (book: Book | SeachBook) => {
  if (!book.kind) return []
  return book.kind
    .split(',')
    .map(t => t.trim())
    .filter(t => t && !/^\d{4}\//.test(t) && !['VIP', '连载', '完结'].includes(t))
    .slice(0, 2)
}

const subJustify = computed(() =>
  props.isSearch ? 'space-between' : 'flex-start',
)
</script>
