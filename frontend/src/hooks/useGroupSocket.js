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
 *   - Fetch the authoritative full state via REST on mount and after reconnects.
 *     If the initial request is still in flight when the first connection opens,
 *     it buffers subscribed deltas and doubles as the connect resync; otherwise
 *     connect starts a fresh resync. Delta-only state is never trusted after a
 *     dropped connection (Requirement 7.2, task 10.7).
 *   - Subscribe to `/topic/group/{groupId}` and merge each delta into state
 *     (task 10.3).
 *
 * @param {string|number} groupId - group whose channel to subscribe to.
 * @param {(delta: object) => void} [onDelta] - optional side-channel callback
 *        invoked for each live update (e.g. toast notifications).
 * @returns {{ connected: boolean, leaderboard: object|null, resync: () => Promise<void>, error: Error|null }}
 */
export function useGroupSocket(groupId, onDelta) {
  const [connected, setConnected] = useState(false)
  const [leaderboard, setLeaderboard] = useState(null)
  const [error, setError] = useState(null)
  const clientRef = useRef(null)
  const onDeltaRef = useRef(onDelta)
  const groupIdRef = useRef(groupId)
  const mountedRef = useRef(true)
  const nextResyncRequestRef = useRef(0)
  const latestRequestedResyncRef = useRef(0)
  const latestAppliedResyncRef = useRef(0)
  const pendingResyncsRef = useRef(new Set())

  // Make stale requests from a previous group unable to update this group.
  groupIdRef.current = groupId

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  // Keep the latest callback without re-subscribing on every render.
  useEffect(() => {
    onDeltaRef.current = onDelta
  }, [onDelta])

  /**
   * Pull an authoritative leaderboard snapshot. Deltas received after this
   * request starts are buffered and replayed over the snapshot, so a slow REST
   * response cannot roll live state back. Overlapping snapshots are applied in
   * request order, never allowing an older completion to replace a newer one.
   */
  const resync = useCallback(async () => {
    if (groupId == null) return

    const requestedGroupId = groupId
    const requestId = ++nextResyncRequestRef.current
    latestRequestedResyncRef.current = requestId
    const pending = { groupId: requestedGroupId, deltas: [] }
    pendingResyncsRef.current.add(pending)
    setError(null)

    try {
      const state = await api.getLeaderboard(requestedGroupId)
      if (
        !mountedRef.current ||
        groupIdRef.current !== requestedGroupId ||
        requestId < latestAppliedResyncRef.current
      ) {
        return
      }

      latestAppliedResyncRef.current = requestId
      const reconciled = pending.deltas.reduce(
        (board, delta) => mergeDelta(board, delta),
        state,
      )
      setLeaderboard(reconciled)
      setError(null)
    } catch (err) {
      if (
        mountedRef.current &&
        groupIdRef.current === requestedGroupId &&
        requestId === latestRequestedResyncRef.current
      ) {
        setError(err instanceof Error ? err : new Error('Failed to resync leaderboard'))
      }
      throw err
    } finally {
      pendingResyncsRef.current.delete(pending)
    }
  }, [groupId])

  // Initial state load over REST, independent of the WebSocket lifecycle so the
  // leaderboard renders immediately even before the socket connects (task 10.3).
  useEffect(() => {
    if (groupId == null) {
      setLeaderboard(null)
      setError(null)
      return
    }
    setLeaderboard(null)
    setError(null)
    void resync().catch(() => {})
  }, [groupId, resync])

  useEffect(() => {
    if (groupId == null) return undefined

    const client = new Client({
      // SockJS gives us the HTTP-fallback transport Spring configures.
      webSocketFactory: () => new SockJS(WS_URL),
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true)

        // Subscribe before starting reconnect resync so updates are captured as
        // promptly as possible and buffered while the snapshot is in flight.
        client.subscribe(`/topic/group/${groupId}`, (message) => {
          try {
            if (groupIdRef.current !== groupId) return
            const delta = JSON.parse(message.body)
            for (const pending of pendingResyncsRef.current) {
              if (pending.groupId === groupId) pending.deltas.push(delta)
            }
            // Delta-merge (task 10.3): fold the update into leaderboard state.
            setLeaderboard((prev) => mergeDelta(prev, delta))
            onDeltaRef.current?.(delta)
          } catch {
            // Ignore malformed frames; the next resync will correct state.
          }
        })

        // If the mount-time snapshot is still pending, it already buffers
        // frames from this new subscription; avoid issuing a duplicate request.
        // A completed initial snapshot is re-fetched here to close the gap before
        // subscription, and every later reconnect always takes this path.
        const resyncAlreadyPending = [...pendingResyncsRef.current].some(
          (pending) => pending.groupId === groupId,
        )
        if (!resyncAlreadyPending) void resync().catch(() => {})
      },
      onDisconnect: () => setConnected(false),
      onWebSocketClose: () => setConnected(false),
    })

    clientRef.current = client
    client.activate()

    return () => {
      // Unsubscribe/teardown on unmount or groupId change. STOMP deactivation is
      // asynchronous, so attach a handler rather than leaving a rejected promise.
      void client.deactivate().catch(() => {})
      clientRef.current = null
      setConnected(false)
    }
  }, [groupId, resync])

  return { connected, leaderboard, resync, error }
}

export default useGroupSocket
