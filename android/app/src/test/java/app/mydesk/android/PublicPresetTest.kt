package app.mydesk.android

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class PublicPresetTest {
    @Test fun newTasksRequireTheUsersOwnRepositoryOwner() {
        for (kind in listOf("custom","52fzwg","glados")) {
            assertEquals("",githubPreset(kind)["owner"]!!.jsonPrimitive.content)
        }
    }
    @Test fun customTaskKeepsWorkflowStatusAndDailyTimeout() {
        val preset=githubPreset("custom")
        assertEquals("workflow",preset["adapter"]!!.jsonPrimitive.content)
        assertEquals(36,preset["max_age_hours"]!!.jsonPrimitive.int)
    }
}
