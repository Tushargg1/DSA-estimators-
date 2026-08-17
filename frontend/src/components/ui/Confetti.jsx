import { useEffect, useRef } from 'react'

const COLORS = ['#8b7cff', '#4ed9c5', '#45d49a', '#f4ce72', '#ff7485', '#f1b75b']
const PARTICLE_COUNT = 60

function randomBetween(min, max) {
  return Math.random() * (max - min) + min
}

function Confetti({ active, duration = 3000 }) {
  const canvasRef = useRef(null)
  const animRef = useRef(null)

  useEffect(() => {
    if (!active) return
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    canvas.width = window.innerWidth
    canvas.height = window.innerHeight

    const particles = Array.from({ length: PARTICLE_COUNT }, () => ({
      x: randomBetween(0, canvas.width),
      y: randomBetween(-canvas.height * 0.3, 0),
      vx: randomBetween(-3, 3),
      vy: randomBetween(2, 7),
      rotation: randomBetween(0, 360),
      rotationSpeed: randomBetween(-5, 5),
      width: randomBetween(6, 12),
      height: randomBetween(4, 8),
      color: COLORS[Math.floor(Math.random() * COLORS.length)],
      opacity: 1,
    }))

    const startTime = performance.now()

    const animate = (now) => {
      const elapsed = now - startTime
      if (elapsed > duration) {
        ctx.clearRect(0, 0, canvas.width, canvas.height)
        return
      }

      ctx.clearRect(0, 0, canvas.width, canvas.height)
      const fadeStart = duration * 0.7
      const globalOpacity = elapsed > fadeStart ? 1 - (elapsed - fadeStart) / (duration - fadeStart) : 1

      for (const p of particles) {
        p.x += p.vx
        p.y += p.vy
        p.vy += 0.12 // gravity
        p.rotation += p.rotationSpeed
        p.vx *= 0.99

        ctx.save()
        ctx.translate(p.x, p.y)
        ctx.rotate((p.rotation * Math.PI) / 180)
        ctx.globalAlpha = globalOpacity * p.opacity
        ctx.fillStyle = p.color
        ctx.fillRect(-p.width / 2, -p.height / 2, p.width, p.height)
        ctx.restore()
      }

      animRef.current = requestAnimationFrame(animate)
    }

    animRef.current = requestAnimationFrame(animate)
    return () => { if (animRef.current) cancelAnimationFrame(animRef.current) }
  }, [active, duration])

  if (!active) return null

  return (
    <canvas
      ref={canvasRef}
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 9999,
        pointerEvents: 'none',
      }}
      aria-hidden="true"
    />
  )
}

export default Confetti
