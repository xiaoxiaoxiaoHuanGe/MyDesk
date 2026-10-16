package app.mydesk.android

import kotlinx.serialization.Serializable
import java.time.*

@Serializable data class ReminderRepeat(val days: List<Int>,val hour: Int,val minute: Int,val timezone: String,val seed: String="") {
    fun next(after: Instant): Instant {
        val zone=ZoneId.of(timezone)
        val date=after.atZone(zone).toLocalDate()
        for(offset in 0L..8L) {
            val day=date.plusDays(offset)
            if(day.dayOfWeek.value !in days) continue
            val local=day.atTime(hour,minute)
            val offsets=zone.rules.getValidOffsets(local)
            if(offsets.isEmpty()) continue
            val candidate=local.toInstant(offsets.first())
            if(candidate>after) return candidate
        }
        error("无法计算下一次提醒时间")
    }
    fun revision(at: Instant)="${seed}_${at.epochSecond}"
    fun label(): String=when(days.sorted()) {
        (1..7).toList()->"每天"
        (1..5).toList()->"周一至周五"
        listOf(6,7)->"周末"
        else->"每周"+days.sorted().joinToString("、") {listOf("一","二","三","四","五","六","日")[it-1]}
    }
}

internal fun nextReminderMinute(now: Instant,zone: ZoneId)=now.atZone(zone).plusMinutes(1).withSecond(0).withNano(0)

/** Project the latest occurrence from a cached rule even when the phone is offline. */
internal fun currentOccurrence(reminder: Reminder,now: Instant): Reminder {
    val repeat=reminder.repeat ?: return reminder
    if(reminder.status !in setOf("pending","snoozed")) return reminder
    val scheduled=Instant.parse(reminder.scheduledAt ?: reminder.remindAt)
    if(repeat.next(scheduled)>now) return reminder
    var latest=repeat.next(now.minusSeconds(8*86400))
    while(true) {
        val following=repeat.next(latest)
        if(following>now) break
        latest=following
    }
    return reminder.copy(remindAt=latest.toString(),scheduledAt=latest.toString(),status="pending",revision=repeat.revision(latest))
}

internal fun followingOccurrence(reminder: Reminder,after: Instant): Reminder {
    val repeat=checkNotNull(reminder.repeat)
    val whenNext=repeat.next(maxOf(after,Instant.parse(reminder.scheduledAt ?: reminder.remindAt)))
    return reminder.copy(remindAt=whenNext.toString(),scheduledAt=whenNext.toString(),status="pending",revision=repeat.revision(whenNext))
}
