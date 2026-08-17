import { createContext, useCallback, useContext, useRef, useState } from 'react'

const ToastContext = createContext(null)

let toastId = 0

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([])
  const timersRef = useRef({})

  const addToast = useCallback((message, { type = 'info', duration = 4000 } = {}) => {
    const id = ++toastId
    setToasts((current) => [...current, { id, message, type }])
    timersRef.current[id] = setTimeout(() => {
      setToasts((current) => current.filter((t) => t.id !== id))
      delete timersRef.current[id]
    }, duration)
    return id
  }, [])

  const removeToast = useCallback((id) => {
    setToasts((current) => current.filter((t) => t.id !== id))
    if (timersRef.current[id]) {
      clearTimeout(timersRef.current[id])
      delete timersRef.current[id]
    }
  }, [])

  return (
    <ToastContext.Provider value={{ addToast, removeToast }}>
      {children}
      {toasts.length > 0 && (
        <div className="toast-container" aria-live="polite">
          {toasts.map((toast) => (
            <div key={toast.id} className={`toast toast-${toast.type}`} onClick={() => removeToast(toast.id)}>
              <span className="toast-icon">
                {toast.type === 'success' ? '\u2713' : toast.type === 'error' ? '!' : '\u2139'}
              </span>
              <span>{toast.message}</span>
            </div>
          ))}
        </div>
      )}
    </ToastContext.Provider>
  )
}

export function useToast() {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used within ToastProvider')
  return ctx
}
