<template>
  <div class="min-h-screen bg-gray-100">
    <header class="bg-white shadow-sm">
      <div class="max-w-7xl mx-auto px-4 py-4 flex items-center justify-between">
        <div class="flex items-center space-x-3">
          <div class="w-10 h-10 bg-blue-500 rounded-lg flex items-center justify-center">
            <svg class="w-6 h-6 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"></path>
            </svg>
          </div>
          <div>
            <h1 class="text-xl font-bold text-gray-800">客流负荷均衡推演系统</h1>
            <p class="text-sm text-gray-500">门店节日活动资源分配</p>
          </div>
        </div>
        <div class="text-sm text-gray-500">
          端口: 前端 8207 | 后端 8307
        </div>
      </div>
    </header>
    
    <nav class="bg-white border-b">
      <div class="max-w-7xl mx-auto px-4">
        <div class="flex space-x-1">
          <router-link 
            v-for="item in navItems" 
            :key="item.path"
            :to="item.path"
            class="px-4 py-3 text-sm font-medium transition-colors"
            :class="isActive(item.path) ? 'text-blue-600 border-b-2 border-blue-600 bg-blue-50' : 'text-gray-600 hover:text-gray-900 hover:bg-gray-50'"
          >
            {{ item.name }}
          </router-link>
        </div>
      </div>
    </nav>
    
    <main class="max-w-7xl mx-auto px-4 py-6">
      <router-view />
    </main>
    
    <footer class="bg-white border-t mt-8">
      <div class="max-w-7xl mx-auto px-4 py-4 text-center text-sm text-gray-500">
        门店节日活动客流负荷均衡资源分配推演系统 © 2024
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()

const navItems = [
  { name: '区域配置', path: '/areas' },
  { name: '场景管理', path: '/scenarios' },
  { name: '临时封区', path: '/closures' },
  { name: '负荷测算', path: '/calculation' },
  { name: '优化推演', path: '/optimization' },
  { name: '落地批次', path: '/batches' },
  { name: '风险监控', path: '/risk' }
]

const isActive = (path: string) => {
  return route.path === path
}
</script>
