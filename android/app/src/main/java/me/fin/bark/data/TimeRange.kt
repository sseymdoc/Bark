package me.fin.bark.data

import me.fin.bark.R
import java.util.Calendar

/** iOS MessageDeleteTimeRange: the first five clear up to now, the "before" ones clear everything older. */
enum class TimeRange(val label: Int) {
    LastHour(R.string.lastHour),
    Today(R.string.today),
    TodayAndYesterday(R.string.todayAndYesterday),
    LastMonth(R.string.lastMonth),
    AllTime(R.string.allTime),
    BeforeOneHour(R.string.beforeAnHour),
    BeforeToday(R.string.beforeToday),
    BeforeYesterday(R.string.beforeYesterday),
    BeforeOneMonth(R.string.beforeAMonth),
    ;

    /** Inclusive [start, end] in epoch ms. */
    fun bounds(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        fun cal(block: Calendar.() -> Unit) = Calendar.getInstance().apply { timeInMillis = now; block() }.timeInMillis
        val startOfToday = cal { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val yesterday = Calendar.getInstance().apply { timeInMillis = startOfToday; add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
        val lastHour = cal { add(Calendar.HOUR_OF_DAY, -1) }
        val lastMonth = cal { add(Calendar.MONTH, -1) }
        return when (this) {
            LastHour -> lastHour to now
            Today -> startOfToday to now
            TodayAndYesterday -> yesterday to now
            LastMonth -> lastMonth to now
            AllTime -> 0L to now
            BeforeOneHour -> 0L to lastHour
            BeforeToday -> 0L to startOfToday
            BeforeYesterday -> 0L to yesterday
            BeforeOneMonth -> 0L to lastMonth
        }
    }

    companion object {
        val main = listOf(LastHour, Today, TodayAndYesterday, LastMonth, AllTime)
        val more = listOf(BeforeOneHour, BeforeToday, BeforeYesterday, BeforeOneMonth)
    }
}
