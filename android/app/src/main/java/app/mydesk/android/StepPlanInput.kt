package app.mydesk.android

import kotlinx.serialization.json.*

internal data class StepPlanValues(val start: Int,val increment: Int,val interval: Int,val target: Int,val randomPercent: Int=0) {
    private val spread=increment*randomPercent/100
    val low=maxOf(1,increment-spread)
    val high=increment+spread
    val minCount=(target-start)/high+2
    val maxCount=(target-start)/low+2
    val count=(target-start)/increment+2
    val final=start+(count-1)*increment
    val duration=(count-1)*interval
    val durationLabel=if(randomPercent==0) "$duration" else "${(minCount-1)*interval}～${(maxCount-1)*interval}"
    val estimateLabel=if(randomPercent==0) "$count 次 · 最后 $final 步" else "$minCount～$maxCount 次 · 最后 ${target+1}～${minOf(target+high,30000)} 步"
    fun json()=buildJsonObject {put("start",start);put("increment",increment);put("interval_minutes",interval);put("target",target);put("random_percent",randomPercent)}
}
internal object StepPlanInput {
    fun parse(start: String,increment: String,interval: String,target: String,randomPercent: Int=0): StepPlanValues {
        val values=listOf(start,increment,interval,target).map {value->
            require(value.matches(Regex("[0-9]+"))) {"请填写四项整数参数"}
            value.toIntOrNull() ?: error("数值过大")
        }
        val (s,i,m,t)=values
        require(s in 0..29999 && t in s..29999 && i in 1..30000 && m in 1..1440 && randomPercent in setOf(0,10)) {"终止步数小于 30000，起始不高于终止，增量大于零，间隔为 1–1440 分钟"}
        val result=StepPlanValues(s,i,m,t,randomPercent)
        require(randomPercent!=0||result.final<=30000) {"最后超过终止步数的提交也必须不超过 30000 步"}
        return result
    }
}
