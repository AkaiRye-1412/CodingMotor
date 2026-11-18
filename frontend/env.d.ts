/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  // SFC 的实际类型由 vue-tsc 解析；此声明仅兜底非 Volar/vue-tsc 场景
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const component: DefineComponent<object, object, any>
  export default component
}
