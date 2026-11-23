package dev.tsdroid.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the account snapshot's shape as the core emits it.
 *
 * The account pane is the first consumer of `snapshot.account`; a renamed key
 * would not throw, it would just render an empty pane. The sample below is a
 * trimmed copy of tsbridge's own `docs/events.md` example.
 */
class AccountStateTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesTheCoreShape() {
        val state = json.decodeFromString<AccountState>(
            """
            {
              "loggedIn": true,
              "email": "a@b.c",
              "username": "ann",
              "uuid": "u-1",
              "keyBytes": 32,
              "busy": false,
              "command": "sync_backup",
              "lastError": "",
              "lastBackup": "/data/backups/m.json",
              "progress": "",
              "itemCount": 2,
              "mytsId": "AAAA",
              "mytsPublicKey": "BBBB",
              "items": [
                {
                  "uuid": "f-1", "class": "ITEM_FOLDER", "classId": 6,
                  "parent": "", "sortOrder": "",
                  "folder": { "name": "Favs", "type": "BOOKMARK", "typeId": 1 }
                },
                {
                  "uuid": "b-1", "class": "BOOKMARK", "classId": 1,
                  "parent": "f-1", "sortOrder": "",
                  "bookmark": {
                    "name": "Home", "address": "ts.example.com", "port": 9987,
                    "nickname": "ann", "defaultChannel": "Lobby",
                    "hasServerPassword": true, "hasChannelPassword": false
                  }
                }
              ],
              "hierarchy": {
                "entries": [
                  {"uuid": "f-1", "parent": "", "depth": 0, "name": "Favs",
                   "folderFor": "BOOKMARK", "isFolder": true},
                  {"uuid": "b-1", "parent": "f-1", "depth": 1, "name": "Home",
                   "folderFor": null, "isFolder": false}
                ],
                "unplaced": [], "folders": 1, "roots": 1
              }
            }
            """,
        )

        assertTrue(state.loggedIn)
        assertEquals("ann", state.username)
        assertTrue(state.hasMytsId)
        assertEquals(2, state.items.size)

        val bookmark = state.item("b-1")?.bookmark
        assertTrue("bookmark was not parsed", bookmark != null)
        assertEquals("ts.example.com", bookmark!!.address)
        // The protocol default is omitted, so the connect field gets a bare host.
        assertEquals("ts.example.com", bookmark.connectAddress)
        assertEquals("Lobby", bookmark.defaultChannel)
        assertTrue("password presence was dropped", bookmark.hasServerPassword)

        // Folders survive the pre-order walk and keep their nesting depth.
        val rows = state.bookmarkRows()
        assertEquals(2, rows.size)
        assertEquals("Favs", rows[0].name)
        assertTrue(rows[0].folder)
        assertEquals(1, rows[1].depth)
        assertEquals("Home", rows[1].bookmark?.name)
    }

    @Test
    fun nonDefaultPortsBecomePartOfTheAddress() {
        val bookmark = json.decodeFromString<AccountBookmark>(
            """{"name":"x","address":"host","port":10011}""",
        )
        assertEquals("host:10011", bookmark.connectAddress)

        val unset = json.decodeFromString<AccountBookmark>("""{"address":"host"}""")
        assertEquals("host", unset.connectAddress)
    }
}
