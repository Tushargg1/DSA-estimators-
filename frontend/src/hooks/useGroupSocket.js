import { useCallback, useEffect, useRef, useState } from 'react'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { api } from '../api/client.js'

const configuredWs = import.meta.env.VITE_WS_URL
const localBrowser = ['localhost', '127.0.0.1'].includes(globalThis.location?.hostname)
if (import.meta.env.PROD && !configuredWs && !localBrowser) {
  throw new Error('VITE_WS_URL is required for production deployments')
}
if (configuredWs) {
  let parsed
  try { parsed = new URL(configuredWs) } catch { throw new Error('VITE_WS_URL must be an absolute URL') }
  if (parsed.protocol === 'ws:' || parsed.protocol === 'wss:') {
    throw new Error('VITE_WS_URL must be an HTTP(S) SockJS URL ending in /ws')
  }
  if (import.meta.env.PROD && !localBrowser && parsed.protocol !== 'https:') {
    throw new Error('VITE_WS_URL must use HTTPS in production')
  }
}
export const WS_URL = configuredWs || 'http://localhost:8080/ws'

export function mergeDelta(board, delta) {
  if (!board || !Array.isArray(board.members) || !delta) return board
  let matched = false
  const members = board.members.map((member) => {
    if (member.userId !== delta.userId) return member
    matched = true
    return {
      ...member,
      userName: delta.userName ?? member.userName,
      todayCount: delta.newDailyCount,
      dailyTarget: delta.target ?? member.dailyTarget,
    }
  })
  if (!matched) {
    members.push({
      userId: delta.userId, userName: delta.userName ?? 'Unknown',
      todayCount: delta.newDailyCount, dailyTarget: delta.target ?? 0,
      currentStreak: 0, longestStreak: 0, totalSolved: 0,
    })
  }
  return { ...board, members }
}

export function useGroupSocket(groupId, token, onDelta, tokenVersion = 0) {
  const [connected, setConnected] = useState(false)
  const [connectionGeneration, setConnectionGeneration] = useState(0)
  const [leaderboard, setLeaderboard] = useState(null)
  const [error, setError] = useState(null)
  const onDeltaRef = useRef(onDelta)
  const groupIdRef = useRef(groupId)
  const mountedRef = useRef(true)
  const nextRequest = useRef(0)
  const latestApplied = useRef(0)
  const pendingResyncs = useRef(new Set())
  groupIdRef.current = groupId

  useEffect(() => {
    mountedRef.current = true
    return () => { mountedRef.current = false }
  }, [])
  useEffect(() => { onDeltaRef.current = onDelta }, [onDelta])

  const resync = useCallback(async () => {
    if (groupId == null || !token) return
    const requestedGroup = groupId
    const requestId = ++nextRequest.current
    const pending = { groupId: requestedGroup, deltas: [] }
    pendingResyncs.current.add(pending)
    try {
      const state = await api.getLeaderboard(requestedGroup)
      if (!mountedRef.current || groupIdRef.current !== requestedGroup
        || requestId !== nextRequest.current || requestId < latestApplied.current) return
      latestApplied.current = requestId
      setLeaderboard(pending.deltas.reduce(mergeDelta, state))
      setError(null)
    } catch (requestError) {
      if (mountedRef.current && groupIdRef.current === requestedGroup
        && requestId === nextRequest.current) setError(requestError)
      throw requestError
    } finally {
      pendingResyncs.current.delete(pending)
    }
  }, [groupId, token])

  useEffect(() => {
    if (groupId == null || !token) {
      setLeaderboard(null)
      setError(null)
      return
    }
    setLeaderboard(null)
    void resync().catch(() => {})
  }, [groupId, token, resync])

  useEffect(() => {
    if (groupId == null || !token) return undefined
    const client = new Client({
      webSocketFactory: () => new SockJS(WS_URL),
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true)
        setConnectionGeneration((value) => value + 1)
        setError(null)
        client.subscribe(`/topic/group/${groupId}`, (message) => {
          try {
            if (groupIdRef.current !== groupId) return
            const delta = JSON.parse(message.body)
            for (const pending of pendingResyncs.current) {
              if (pending.groupId === groupId) pending.deltas.push(delta)
            }
            setLeaderboard((current) => mergeDelta(current, delta))
            onDeltaRef.current?.(delta)
          } catch (parseError) {
            setError(new Error('Received an invalid live update'))
          }
        })
        const pending = [...pendingResyncs.current].some((item) => item.groupId === groupId)
        if (!pending) void resync().catch(() => {})
      },
      onStompError: (frame) => {
        setConnected(false)
        setError(new Error(frame.headers?.message || 'Live connection authorization failed'))
      },
      onWebSocketError: () => setError(new Error('Live connection failed')),
      onWebSocketClose: () => setConnected(false),
      onDisconnect: () => setConnected(false),
    })
    client.activate()
    return () => {
      void client.deactivate().catch(() => {})
      setConnected(false)
    }
  }, [groupId, token, tokenVersion, resync])

  return { connected, connectionGeneration, leaderboard, resync, error }
}

export default useGroupSocket
