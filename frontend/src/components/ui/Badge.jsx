import { cn } from './cn.js'

const variants = {
  default: 'bg-slate-500/10 text-slate-300 border-slate-500/20',
  brand: 'bg-purple-500/10 text-purple-300 border-purple-500/20',
  success: 'bg-emerald-500/10 text-emerald-300 border-emerald-500/20',
  warning: 'bg-amber-500/10 text-amber-300 border-amber-500/20',
  danger: 'bg-rose-500/10 text-rose-300 border-rose-500/20',
  aqua: 'bg-teal-500/10 text-teal-300 border-teal-500/20',
}

function Badge({ children, variant = 'default', className }) {
  return (
    <span className={cn(
      'inline-flex items-center gap-1 px-2 py-0.5 text-[11px] font-semibold rounded-md border',
      variants[variant],
      className
    )}>
      {children}
    </span>
  )
}

export default Badge
