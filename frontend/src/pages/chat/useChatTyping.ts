import { useCallback, useEffect, useRef, useState } from "react";
import { sendTyping } from "../../services/chatService";

// Typing is transient: throttle activity and expire lost stop/disconnect hints.
export default function useChatTyping(selectedId: string | null) {
  const [typingIds, setTypingIds] = useState<Set<string>>(() => new Set());
  const receivedTimers = useRef(new Map<string, number>());
  const sender = useRef<((active: boolean) => void) | null>(null);

  const receiveTyping = useCallback((id: string, active: boolean) => {
    window.clearTimeout(receivedTimers.current.get(id));
    receivedTimers.current.delete(id);
    function update(value: boolean) {
      setTypingIds((previous) => {
        const next = new Set(previous);
        if (value) next.add(id);
        else next.delete(id);
        return next;
      });
    }
    update(active);
    if (active) {
      receivedTimers.current.set(
        id,
        window.setTimeout(() => {
          receivedTimers.current.delete(id);
          update(false);
        }, 5000),
      );
    }
  }, []);

  useEffect(() => {
    let lastSent = 0;
    let active = false;
    let idle: number | undefined;
    let pending: boolean | undefined;
    let busy = false;
    // Serialize and coalesce requests so a delayed start cannot follow its stop.
    async function flush() {
      if (busy || pending === undefined || !selectedId) return;
      const value = pending;
      pending = undefined;
      busy = true;
      try {
        await sendTyping(selectedId, value);
      } catch {
        // A lost typing hint must never interrupt composing or sending a message.
      } finally {
        busy = false;
        if (pending !== undefined) void flush();
      }
    }
    function publish(value: boolean) {
      pending = value;
      void flush();
    }
    function update(value: boolean) {
      window.clearTimeout(idle);
      if (value) {
        if (!active || Date.now() - lastSent >= 2000) {
          publish(true);
          lastSent = Date.now();
        }
        active = true;
        idle = window.setTimeout(() => update(false), 2500);
      } else if (active) {
        active = false;
        publish(false);
      }
    }
    sender.current = update;
    return () => {
      update(false);
      sender.current = null;
    };
  }, [selectedId]);

  useEffect(() => {
    const timers = receivedTimers.current;
    return () => {
      timers.forEach((timer) => window.clearTimeout(timer));
      timers.clear();
    };
  }, []);

  const updateTyping = useCallback(
    (active: boolean) => sender.current?.(active),
    [],
  );
  return {
    isTyping: selectedId !== null && typingIds.has(selectedId),
    receiveTyping,
    updateTyping,
  };
}
