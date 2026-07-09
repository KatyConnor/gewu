import { useState, useEffect, useRef, useCallback } from 'react'

const PACE_MS = 16
const MIN_GAP_TO_PACE = 20

function step(revealed: number, total: number): number {
  const remaining = total - revealed
  if (remaining <= 4) return remaining
  if (remaining <= 20) return 2
  if (remaining <= 100) return 4
  if (remaining <= 500) return 8
  return Math.min(64, Math.ceil(remaining / 8))
}

export function usePacedText(source: string, isLive: boolean) {
  const [displayed, setDisplayed] = useState('')
  const shownRef = useRef('')
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const lastSourceLengthRef = useRef(0)

  const clearTimer = useCallback(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current)
      timerRef.current = null
    }
  }, [])

  const sync = useCallback((text: string) => {
    shownRef.current = text
    setDisplayed(text)
  }, [])

  const run = useCallback(() => {
    timerRef.current = null
    if (!source) {
      sync('')
      return
    }

    if (!isLive) {
      sync(source)
      return
    }

    if (!source.startsWith(shownRef.current) || source.length < shownRef.current.length) {
      sync(source)
      lastSourceLengthRef.current = source.length
      return
    }

    if (source.length <= shownRef.current.length) return

    const gap = source.length - shownRef.current.length
    const newContent = source.length - lastSourceLengthRef.current
    lastSourceLengthRef.current = source.length

    if (gap <= MIN_GAP_TO_PACE || newContent > 100) {
      sync(source)
      return
    }

    const paceAmount = step(shownRef.current.length, source.length)
    const end = Math.min(source.length, shownRef.current.length + paceAmount)
    sync(source.slice(0, end))

    if (end < source.length) {
      const delay = gap > 200 ? PACE_MS / 2 : PACE_MS
      timerRef.current = setTimeout(run, delay)
    }
  }, [source, isLive, sync])

  useEffect(() => {
    clearTimer()
    run()
  }, [source, isLive, clearTimer, run])

  useEffect(() => {
    return () => {
      clearTimer()
    }
  }, [clearTimer])

  const flush = useCallback(() => {
    clearTimer()
    sync(source || '')
    lastSourceLengthRef.current = (source || '').length
  }, [source, clearTimer, sync])

  return { displayed, flush }
}
