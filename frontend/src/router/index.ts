import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/areas'
    },
    {
      path: '/areas',
      name: 'AreaConfig',
      component: () => import('@/views/AreaConfig.vue')
    },
    {
      path: '/scenarios',
      name: 'ScenarioManage',
      component: () => import('@/views/ScenarioManage.vue')
    },
    {
      path: '/closures',
      name: 'ClosureControl',
      component: () => import('@/views/ClosureControl.vue')
    },
    {
      path: '/calculation',
      name: 'LoadCalculation',
      component: () => import('@/views/LoadCalculation.vue')
    },
    {
      path: '/optimization',
      name: 'Optimization',
      component: () => import('@/views/Optimization.vue')
    },
    {
      path: '/batches',
      name: 'BatchLanding',
      component: () => import('@/views/BatchLanding.vue')
    },
    {
      path: '/risk',
      name: 'RiskMonitor',
      component: () => import('@/views/RiskMonitor.vue')
    }
  ]
})

export default router
