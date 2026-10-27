package io.github.hypercar.session

/** 原生回调必须属于当前引擎；校验和短状态更新在同一把锁内完成。 */
internal class NativeSessionGate {
    private var sequence = 0L
    private var active = 0L

    @Synchronized fun begin(): Long = (++sequence).also { active = it }
    @Synchronized fun startIf(allowed: () -> Boolean, start: (Long) -> Boolean): Boolean {
        if (!allowed()) return false
        val generation = begin()
        return try {
            start(generation).also { if (!it) invalidateIfCurrent(generation) }
        } catch (error: Throwable) {
            invalidateIfCurrent(generation)
            throw error
        }
    }
    @Synchronized fun stop(stop: () -> Unit) {
        invalidate()
        stop()
    }
    @Synchronized fun invalidate() { active = 0L }
    @Synchronized fun isCurrent(generation: Long): Boolean = generation > 0 && generation == active
    @Synchronized fun invalidateIfCurrent(generation: Long) {
        if (active == generation) active = 0L
    }
    @Synchronized fun ifCurrent(generation: Long, action: () -> Unit): Boolean {
        if (generation <= 0 || generation != active) return false
        action()
        return true
    }
}
