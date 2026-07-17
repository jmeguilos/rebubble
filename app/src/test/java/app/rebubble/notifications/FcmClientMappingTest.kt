package app.rebubble.notifications

import app.rebubble.data.remote.loadFixture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure mapping: `/fcm/client` fixture JSON → [FirebaseOptionsParams].
 * No Firebase classes involved.
 */
class FcmClientMappingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `fixture maps applicationId apiKey projectId gcmSenderId exactly`() {
        val envelope = json.parseToJsonElement(loadFixture("fcm-client.json")).jsonObject
        val data = envelope["data"]!!.jsonObject

        val params = fcmClientToFirebaseOptions(data)

        assertEquals("1:123456789012:android:abcdef1234567890", params.applicationId)
        assertEquals("AIzaSyDUMMYKEYFORFIXTUREUSEONLY0000", params.apiKey)
        assertEquals("rebubble-test", params.projectId)
        assertEquals("123456789012", params.gcmSenderId)
        assertEquals("rebubble-test.appspot.com", params.storageBucket)
        assertEquals("https://rebubble-test.firebaseio.com", params.databaseUrl)
    }

    @Test
    fun `missing oauth_client is tolerated`() {
        val data = buildJsonObject {
            putJsonObject("project_info") {
                put("project_number", "999")
                put("project_id", "proj")
                put("storage_bucket", "proj.appspot.com")
            }
            putJsonArray("client") {
                add(
                    buildJsonObject {
                        putJsonObject("client_info") {
                            put("mobilesdk_app_id", "1:999:android:abc")
                        }
                        putJsonArray("api_key") {
                            add(buildJsonObject { put("current_key", "key") })
                        }
                        // no oauth_client
                    },
                )
            }
        }

        val params = fcmClientToFirebaseOptions(data)
        assertEquals("1:999:android:abc", params.applicationId)
        assertEquals("key", params.apiKey)
        assertEquals("proj", params.projectId)
        assertEquals("999", params.gcmSenderId)
        assertNull(params.databaseUrl)
    }

    @Test
    fun `malformed missing project_info throws typed error`() {
        val data = buildJsonObject {
            putJsonArray("client") {
                add(buildJsonObject {})
            }
        }

        val ex = assertThrows(FcmClientMappingException::class.java) {
            fcmClientToFirebaseOptions(data)
        }
        assertTrue(ex.message!!.contains("project_info"))
    }

    @Test
    fun `malformed empty client array throws typed error`() {
        val data = buildJsonObject {
            putJsonObject("project_info") {
                put("project_number", "1")
                put("project_id", "p")
            }
            putJsonArray("client") {}
        }

        assertThrows(FcmClientMappingException::class.java) {
            fcmClientToFirebaseOptions(data)
        }
    }

    @Test
    fun `malformed missing api_key throws typed error`() {
        val data = buildJsonObject {
            putJsonObject("project_info") {
                put("project_number", "1")
                put("project_id", "p")
            }
            putJsonArray("client") {
                add(
                    buildJsonObject {
                        putJsonObject("client_info") {
                            put("mobilesdk_app_id", "1:1:android:x")
                        }
                    },
                )
            }
        }

        val ex = assertThrows(FcmClientMappingException::class.java) {
            fcmClientToFirebaseOptions(data)
        }
        assertTrue(ex.message!!.contains("api_key"))
    }

    /** Sanity: fixture data is a [JsonObject] suitable for the mapper input type. */
    @Test
    fun `fixture data is JsonObject`() {
        val envelope = json.parseToJsonElement(loadFixture("fcm-client.json")).jsonObject
        assertTrue(envelope["data"] is JsonObject)
    }
}
