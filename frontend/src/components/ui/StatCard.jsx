import { cn } from './cn.js'

function StatCard({ icon, label, value, subtitle, trend, trendLabel, accent = 'brand', className }) {
  const accents = {
    brand: 'from-purple-500/20 to-transparent border-purple-500/20',
    aqua: 'from-teal-400/20 to-transparent border-teal-400/20',
    success: 'from-emerald-400/20 to-transparent border-emerald-400/20',
    warning: 'from-amber-400/20 to-transparent border-amber-400/20',
    danger: 'from-rose-400/20 to-transparent border-rose-400/20',
  }

  const iconColors = {
    brand: 'text-purple-400 bg-purple-500/10 border-purple-500/20',
    aqua: 'text-teal-400 bg-teal-500/10 border-teal-500/20',
    success: 'text-emerald-400 bg-emerald-500/10 border-emerald-500/20',
    warning: 'text-amber-400 bg-amber-500/10 border-amber-500/20',
    danger: 'text-rose-400 bg-rose-500/10 border-rose-500/20',
  }

  return (
    <div className={cn(
      'relative overflow-hidden rounded-2xl border border-white/[0.08] bg-gradient-to-br p-5',
      'backdrop-blur-sm transition-all duration-200 hover:border-white/[0.14] hover:shadow-lg',
      accents[accent],
      className
    )}>
      <div className="flex items-start justify-between gap-3">
        <div className="flex-1 min-w-0">
          <p className="text-xs font-medium text-slate-400 mb-1 tracking-wide uppercase">{label}</p>
          <p className="text-2xl font-bold text-white tracking-tight leading-none">{value}</p>
          {subtitle && <p className="text-xs text-slate-500 mt-1.5">{subtitle}</p>}
        </div>
        {icon && (
          <div className={cn('flex items-center justify-center w-10 h-10 rounded-xl border', iconColors[accent])}>
            {icon}
          </div>
        )}
      </div>
      {trend != null && (
        <div className="flex items-center gap-1.5 mt-3">
          <span className={cn(
            'text-xs font-semibold px-1.5 py-0.5 rounded-md',
            trend >= 0 ? 'text-emerald-400 bg-emerald-500/10' : 'text-rose-400 bg-rose-500/10'
          )}>
            {trend >= 0 ? '↑' : '↓'} {Math.abs(trend)}%
          </span>
          {trendLabel && <span className="text-xs text-slate-500">{trendLabel}</span>}
        </div>
      )}
    </div>
  )
}

export default StatCard
