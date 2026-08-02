package app.pigeonsms.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingLinksTest {
    private val id = "12345678"
    private val secret = "A".repeat(43)
    private val api = "https%3A%2F%2Fapi.pigeonsms.aldi.best"

    @Test
    fun parsesOfficialWebAndDeepLinks() {
        val web = PairingLinks.parse("https://pigeonsms.aldi.best/pair?pairing_id=$id&secret=$secret&api=$api")
        val deep = PairingLinks.parse("pigeonsms://pair?pairing_id=$id&secret=$secret&api=$api")

        assertNotNull(web)
        assertNotNull(deep)
        assertEquals(id, web.id)
        assertEquals(secret, deep.secret)
    }

    @Test
    fun rejectsForeignServersAndMalformedSecrets() {
        assertNull(PairingLinks.parse("pigeonsms://pair?pairing_id=$id&secret=$secret&api=https%3A%2F%2Fevil.example"))
        assertNull(PairingLinks.parse("pigeonsms://pair?pairing_id=$id&secret=short&api=$api"))
    }

    @Test
    fun createsIndependentClaimSecrets() {
        val first = PairingLinks.claimSecret()
        val second = PairingLinks.claimSecret()

        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(first))
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(second))
        assertNotEquals(first, second)
    }
}
