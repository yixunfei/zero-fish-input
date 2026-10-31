package dev.zeroinput.copyprobe;

/** Generation and deadline checks are independent of Activity/Service lifetimes. */
final class SessionGate {
    enum State { EMPTY, CAPTURING, READY, REVIEW }
    static final long CAPTURE_MS = 10_000;
    static final long DRAFT_MS = 60_000;
    private long generation;
    private long deadline;
    private State state = State.EMPTY;

    synchronized long begin(long now) {
        generation++;
        state = State.CAPTURING;
        deadline = now + CAPTURE_MS;
        return generation;
    }
    synchronized boolean capturing(long token, long now) {
        return token == generation && state == State.CAPTURING && now < deadline;
    }
    synchronized boolean complete(long token, long now) {
        if (!capturing(token, now)) return false;
        state = State.READY;
        deadline = now + DRAFT_MS;
        return true;
    }
    synchronized boolean claim(long token, long now) {
        if (token != generation || state != State.READY || now >= deadline) return false;
        state = State.REVIEW;
        return true;
    }
    synchronized boolean reviewing(long token, long now) {
        return token == generation && state == State.REVIEW && now < deadline;
    }
    synchronized State state(long now) { return now < deadline ? state : State.EMPTY; }
    synchronized long token() { return generation; }
    synchronized void clear() { generation++; state = State.EMPTY; deadline = 0; }
}
