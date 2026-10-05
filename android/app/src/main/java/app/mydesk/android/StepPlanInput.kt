package app.mydesk.android

import kotlinx.serialization.json.*

internal data class StepPlanValues(val start: Int,val increment: Int,val interval: Int,val target: Int) {
    val count=(target-start)/increment+2
    val final=start+(count-1)*increment
    val duration=(count-1)*interval
    fun json()=buildJsonObject {put("start",start);put("increment",increment);put("interval_minutes",interval);put("target",target)}
}
internal object StepPlanInput {
    fun parse(start: String,increment: String,interval: String,target: String): StepPlanValues {
        val values=listOf(start,increment,interval,target).map {value->
            require(value.matches(Regex("[0-9]+"))) {"请填写四项整数参数"}
            value.toIntOrNull() ?: error("数值过大")
        }
        val (s,i,m,t)=values
        require(s in 0..30000 && t in s..30000 && i in 1..30000 && m in 1..1440) {"起始不高于终止步数，增量大于零，间隔为 1–1440 分钟"}
        val result=StepPlanValues(s,i,m,t)
        require(result.final<=30000) {"最后超过终止步数的提交也必须不超过 30000 步"}
        return result
    }
}
