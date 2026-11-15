package io.github.devngho.herewego.server.store

import io.github.devngho.herewego.server.api.ProfileDto
import io.github.devngho.herewego.server.api.ScheduleDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** A durable, expiring counter. All limits in a reservation are charged atomically. */
data class UsageLimit(
    val key: String,
    val maximum: Int,
    val expiresAt: Long,
)

/**
 * 기기(익명 ID)별 프로필과 일정을 SQLite 파일 하나에 저장한다.
 *
 * MVP 라서 값은 JSON 문자열로 넣는다. 연결은 하나만 열고 호출을 직렬화한다.
 * @param path SQLite 파일 경로. ":memory:" 면 메모리에만 둔다(테스트용).
 */
class Store(
    path: String,
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val connection: Connection

    init {
        if (path != ":memory:") File(path).absoluteFile.parentFile?.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:$path")
        connection.createStatement().use {
            it.executeUpdate(
                "CREATE TABLE IF NOT EXISTS profile (device_id TEXT PRIMARY KEY, body TEXT NOT NULL, updated_at INTEGER NOT NULL)",
            )
            it.executeUpdate(
                "CREATE TABLE IF NOT EXISTS schedule (id TEXT PRIMARY KEY, device_id TEXT NOT NULL, position INTEGER NOT NULL, body TEXT NOT NULL)",
            )
            it.executeUpdate("CREATE INDEX IF NOT EXISTS schedule_device ON schedule (device_id, position)")
            it.executeUpdate("PRAGMA busy_timeout = 5000")
            it.executeUpdate(
                "CREATE TABLE IF NOT EXISTS usage_quota (key TEXT PRIMARY KEY, used INTEGER NOT NULL, expires_at INTEGER NOT NULL)",
            )
        }
    }

    private suspend fun <T> db(block: (Connection) -> T): T = withContext(Dispatchers.IO) { synchronized(connection) { block(connection) } }

    suspend fun profile(deviceId: String): ProfileDto? =
        db { c ->
            c.prepareStatement("SELECT body FROM profile WHERE device_id = ?").use { st ->
                st.setString(1, deviceId)
                st.executeQuery().use { rs -> if (rs.next()) json.decodeFromString<ProfileDto>(rs.getString(1)) else null }
            }
        }

    suspend fun saveProfile(
        deviceId: String,
        profile: ProfileDto,
    ): Unit =
        db { c ->
            c
                .prepareStatement(
                    "INSERT INTO profile (device_id, body, updated_at) VALUES (?, ?, ?) " +
                        "ON CONFLICT(device_id) DO UPDATE SET body = excluded.body, updated_at = excluded.updated_at",
                ).use { st ->
                    st.setString(1, deviceId)
                    st.setString(2, json.encodeToString(ProfileDto.serializer(), profile))
                    st.setLong(3, System.currentTimeMillis())
                    st.executeUpdate()
                }
        }

    suspend fun schedules(deviceId: String): List<ScheduleDto> =
        db { c ->
            c.prepareStatement("SELECT id, body FROM schedule WHERE device_id = ? ORDER BY position, rowid").use { st ->
                st.setString(1, deviceId)
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(json.decodeFromString<ScheduleDto>(rs.getString(2)).copy(id = rs.getString(1))) }
                }
            }
        }

    suspend fun addSchedule(
        deviceId: String,
        schedule: ScheduleDto,
    ): ScheduleDto =
        db { c ->
            val id = UUID.randomUUID().toString()
            val position =
                c.prepareStatement("SELECT COALESCE(MAX(position), -1) + 1 FROM schedule WHERE device_id = ?").use { st ->
                    st.setString(1, deviceId)
                    st.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            c.prepareStatement("INSERT INTO schedule (id, device_id, position, body) VALUES (?, ?, ?, ?)").use { st ->
                st.setString(1, id)
                st.setString(2, deviceId)
                st.setInt(3, position)
                st.setString(4, json.encodeToString(ScheduleDto.serializer(), schedule.copy(id = null)))
                st.executeUpdate()
            }
            schedule.copy(id = id)
        }

    /** @return 이 기기의 일정이 아니거나 없으면 false */
    suspend fun updateSchedule(
        deviceId: String,
        id: String,
        schedule: ScheduleDto,
    ): Boolean =
        db { c ->
            c.prepareStatement("UPDATE schedule SET body = ? WHERE id = ? AND device_id = ?").use { st ->
                st.setString(1, json.encodeToString(ScheduleDto.serializer(), schedule.copy(id = null)))
                st.setString(2, id)
                st.setString(3, deviceId)
                st.executeUpdate() == 1
            }
        }

    suspend fun deleteSchedule(
        deviceId: String,
        id: String,
    ): Boolean =
        db { c ->
            c.prepareStatement("DELETE FROM schedule WHERE id = ? AND device_id = ?").use { st ->
                st.setString(1, id)
                st.setString(2, deviceId)
                st.executeUpdate() == 1
            }
        }

    /** Reserve before sending a paid request, even if it later fails or is cancelled. Null = accepted. */
    suspend fun reserveUsage(
        limits: List<UsageLimit>,
        now: Long,
    ): Long? =
        db { c ->
            require(limits.isNotEmpty() && limits.all { it.maximum >= 0 && it.expiresAt > now })
            c.autoCommit = false
            try {
                c.prepareStatement("DELETE FROM usage_quota WHERE expires_at <= ?").use { st ->
                    st.setLong(1, now)
                    st.executeUpdate()
                }
                for (limit in limits) {
                    val accepted =
                        limit.maximum > 0 &&
                            c
                                .prepareStatement(
                                    "INSERT INTO usage_quota (key, used, expires_at) VALUES (?, 1, ?) " +
                                        "ON CONFLICT(key) DO UPDATE SET used = used + 1 WHERE used < ?",
                                ).use { st ->
                                    st.setString(1, limit.key)
                                    st.setLong(2, limit.expiresAt)
                                    st.setInt(3, limit.maximum)
                                    st.executeUpdate() == 1
                                }
                    if (!accepted) {
                        c.rollback()
                        return@db limit.expiresAt
                    }
                }
                c.commit()
                null
            } catch (e: Exception) {
                c.rollback()
                throw e // Database failure must never bypass the spending cap.
            } finally {
                c.autoCommit = true
            }
        }

    override fun close() = synchronized(connection) { connection.close() }
}
