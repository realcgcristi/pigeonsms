import { createContext, useCallback, useContext, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import './ui.css'

type Toast = { id: number; text: string; tone: 'default' | 'error' }

type ToastApi = {
  show: (text: string) => void
  error: (text: string) => void
}

const ToastContext = createContext<ToastApi>({ show: () => undefined, error: () => undefined })

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])

  const push = useCallback((text: string, tone: 'default' | 'error') => {
    const id = Date.now() + Math.floor(performance.now())
    setToasts((list) => list.concat({ id, text, tone }))
    window.setTimeout(() => setToasts((list) => list.filter((t) => t.id !== id)), 2600)
  }, [])

  const value = useMemo<ToastApi>(
    () => ({ show: (text) => push(text, 'default'), error: (text) => push(text, 'error') }),
    [push],
  )

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className="ui-toasts">
        {toasts.map((toast) => (
          <div key={toast.id} className={toast.tone === 'error' ? 'ui-toast ui-toast--error' : 'ui-toast'}>
            {toast.text}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

export function useToast(): ToastApi {
  return useContext(ToastContext)
}

export default ToastProvider
