import { useCallback, useEffect, useRef, useState } from 'react'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { api } from '../api/client.js'

/**
 * WebSocket URL (STOMP over SockJS). Configurable via `VITE_WS_URL`.
 * Spring Boot exposes the STOMP endpoint at `/ws` with SockJS fallback.
 */
export const WS_URL =
  import.meta.env.VITE_WS_URL ?? 'http://localhost:8080/ws'

/**
 * Merge a single LeaderboardUpdate delta into a leaderboard response.
 *
 * The WebSocket payload (design.md "WebSocket Design" / LeaderboardUpdate DTO):
 *   { userId, userName, problemName, platform, difficulty, newDailyCount, target }
 *
 * The leaderboard member row (LeaderboardResponse.MemberEntry) uses different
 * field names for the same data: the delta's `newDailyCount` is the member's
 * `todayCount`, and the delta's `target` is the member's `dailyTarget`. We map
 * accordingly so the merged state stays in the shape the REST endpoint returns.
 *
 * @param {object|null} board - current leaderboard ({ groupId, groupName, members })
 * @param {object} delta - a LeaderboardUpdate frame
 * @returns {object|null} a new leaderboard object with the matching row updated
 */
export function mergeDelta(board, delta) {
  if (!board || !Array.isArray(board.members) || delta == null) return board

  let matched = false
  const members = board.members.map((m) => {
    if (m.userId !== delta.userId) return m
    matched = true
    return {
      ...m,
      userName: delta.userName ?? m.userName,
      todayCount: delta.newDailyCount,
      dailyTarget: delta.target ?? m.dailyTarget,
    }
  })

  // If the delta references a member we don't have yet (e.g. joined since the
  // last resync), append a minimal row; a full resync will fill the rest.
  if (!matched) {
    members.push({
      userId: delta.userId,
      userName: delta.userName ?? 'Unknown',
      todayCount: delta.newDailyCount,
      dailyTarget: delta.target ?? 0,
      currentStreak: 0,
      longestStreak: 0,
      totalSolved: 0,
    })
  }

  return { ...board, members }
}

/**
 * useGroupSocket — subscribe to a group's live leaderboard channel and keep a
 * merged, always-resyncable copy of the leaderboard.
 *
 * Design contract (design.md — WebSocket Design):
 *   - Fetch the authoritative full state via REST on mount (initial load) and
 *     again on every (re)connect — delta-only state is never trusted after a
 *     dropped connection (Requirement 7.2, task 10.7).
 *   - Subscribe to `/topic/group/{groupId}` and merge each delta into state
 *     (task 10.3).
 *
 * @param {string|number} groupId - group whose channel to subscribe to.
 * @param {(delta: object) => void} [onDelta] - optional side-channel callback
 *        invoked for each live update (e.g. toast notifications).
 * @returns {{ connected: boolean, leaderboard: object|null, resync: () => Promise<void> }}
 */
export function useGroupSocket(groupId, onDelta) {
  const [connected, setConnected] = useState(false)
  const [leaderboard, setLeaderboard] = useState(null)
  const clientRef = useRef(null)
  const onDeltaRef = useRef(onDelta)

  // Keep the latest callback without re-subscribing on every render.
  useEffect(() => {
    onDeltaRef.current = onDelta
  }, [onDelta])

  /**
   * Fallback resync: pull the authoritative full leaderboard over REST and
   * replace local state with it. Called on initial mount and after every
   * (re)connect (task 10.7), and available for manual refresh.
   */
  const resync = useCallback(async () => {
    if (groupId == null) return
    const state = await api.getLeaderboard(groupId)
    setLeaderboard(state)
  }, [groupId])

  // Initial state load over REST, independent of the WebSocket lifecycle so the
  // leaderboard renders immediately even before the socket connects (task 10.3).
  useEffect(() => {
    if (groupId == null) {
      setLeaderboard(null)
      return
    }
    void resync()
  }, [groupId, resync])

  useEffect(() => {
    if (groupId == null) return undefined

    const client = new Client({
      // SockJS gives us the HTTP-fallback transport Spring configures.
      webSocketFactory: () => new SockJS(WS_URL),
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true)
        // Reconnect-resync (task 10.7): re-fetch full state so we never trust
        // delta-only state after a (re)connection, then apply deltas on top.
        void resync()
        client.subscribe(`/topic/group/${groupId}`, (message) => {
          try {
            const delta = JSON.parse(message.body)
            // Delta-merge (task 10.3): fold the update into leaderboard state.
            setLeaderboard((prev) => mergeDelta(prev, delta))
            onDeltaRef.current?.(delta)
          } catch {
            // Ignore malformed frames; the next resync will correct state.
          }
        })
      },
      onDisconnect: () => setConnected(false),
      onWebSocketClose: () => setConnected(false),
    })

    clientRef.current = client
    client.activate()

    return () => {
      // Unsubscribe/teardown on unmount or groupId change.
      void client.deactivate()
      clientRef.current = null
      setConnected(false)
    }
  }, [groupId, resync])

  return { connected, leaderboard, resync }
}

export default useGroupSocket
