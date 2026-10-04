package app.mydesk.android

import org.junit.Assert.*
import org.junit.Test

class BackupInputTest {
    @Test fun shortOrMismatchedPasswordCannotExport() {
        for(value in listOf("", "short")) {
            try {BackupInput.password(value,value);fail("Accepted short backup password")} catch(expected: IllegalArgumentException) {}
        }
        try {BackupInput.password("long-backup-password","another-password");fail("Accepted mismatched backup passwords")} catch(expected: IllegalArgumentException) {}
        BackupInput.password("long-backup-password","long-backup-password")
    }
    @Test fun oversizedOrPlainConfigurationIsRejectedBeforeUpload() {
        for(bytes in listOf(ByteArray(BackupInput.MAX_BYTES+1),"{\"github\":{\"token\":\"secret\"}}".toByteArray())) {
            try {BackupInput.file(bytes);fail("Accepted invalid backup file")} catch(expected: IllegalArgumentException) {}
        }
    }
    @Test fun acceptsEncryptedFileAndSanitizesSuggestedFilename() {
        val file=BackupInput.file("{\"format\":\"mydesk-config-backup\",\"version\":1,\"ciphertext\":\"encrypted\"}".toByteArray())
        assertEquals("encrypted",file.text("ciphertext"))
        assertEquals("MyDesk-backup.mydesk",BackupInput.filename("../../settings.json"))
        assertEquals("MyDesk-20261004.mydesk",BackupInput.filename("MyDesk-20261004.mydesk"))
    }
}
