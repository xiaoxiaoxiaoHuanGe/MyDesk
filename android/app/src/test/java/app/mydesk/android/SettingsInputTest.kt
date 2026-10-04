package app.mydesk.android

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SettingsInputTest {
    @Test fun basicSettingsRequireKnownTimeZoneAndWholeRetentionDays() {
        val value=SettingsInput.basic("Asia/Shanghai","90")
        assertEquals(90,value["history_days"]!!.jsonPrimitive.int)
        assertTrue(runCatching {SettingsInput.basic("Missing/Zone","90")}.isFailure)
        for(days in listOf("0","366","1.5","")) assertTrue(runCatching {SettingsInput.basic("UTC",days)}.isFailure)
    }
    @Test fun networkInputHandlesBlankLinesAndIpv6ButRejectsIncompleteOrAmbiguousRows() {
        val value=SettingsInput.network(true," US = us.example.com \n\n JP=2001:db8::1 ")
        assertEquals(2,value.obj("network").rows("nodes").size)
        assertEquals("2001:db8::1",value.obj("network").rows("nodes")[1].text("host"))
        for(text in listOf("US=","=example.com","US=https://example.com","US=-bad","US=a=b","US=a\nUS=b")) assertTrue(runCatching {SettingsInput.network(true,text)}.isFailure)
        assertTrue(SettingsInput.network(false,"").obj("network").rows("nodes").isEmpty())
    }
    @Test fun taskTimeoutDefaultsTo36HoursAndRejectsNonFiniteOrOutOfRangeValues() {
        assertEquals(36.0,SettingsInput.taskTimeout("  "),0.001)
        assertEquals(1.5,SettingsInput.taskTimeout(" 1.5 "),0.001)
        assertEquals(8760.0,SettingsInput.taskTimeout("8760"),0.001)
        for(text in listOf("NaN","Infinity","0","0.5","8761","abc")) assertTrue(runCatching {SettingsInput.taskTimeout(text)}.isFailure)
    }
}
