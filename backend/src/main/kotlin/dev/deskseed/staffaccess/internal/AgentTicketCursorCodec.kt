package dev.deskseed.staffaccess.internal

import dev.deskseed.ticketing.DefaultStaffView
import dev.deskseed.ticketing.StaffTicketCursor
import dev.deskseed.ticketing.StaffTicketListFilter
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

@Component
internal class AgentTicketCursorCodec {
    fun encode(view: DefaultStaffView, filters: StaffTicketListFilter, cursor: StaffTicketCursor): String {
        val payload = listOf(
            VERSION,
            view.key,
            fingerprint(filters),
            cursor.updatedAt.toString(),
            cursor.ticketNumber.toString(),
        ).joinToString(SEPARATOR)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
    }

    fun decode(view: DefaultStaffView, filters: StaffTicketListFilter, cursor: String): StaffTicketCursor {
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
        }.getOrElse { throw IllegalArgumentException("Invalid ticket cursor") }
        val values = decoded.split(SEPARATOR)
        if (values.size != 5 || values[0] != VERSION || values[1] != view.key || values[2] != fingerprint(filters)) {
            throw IllegalArgumentException("Ticket cursor does not match the selected view and filters")
        }
        return runCatching {
            StaffTicketCursor(Instant.parse(values[3]), values[4].toLong())
        }.getOrElse { throw IllegalArgumentException("Invalid ticket cursor") }
    }

    private fun fingerprint(filters: StaffTicketListFilter): String {
        val canonical = listOf(
            filters.status?.name.orEmpty(),
            filters.priority?.name.orEmpty(),
            filters.groupId?.toString().orEmpty(),
            filters.assignee.orEmpty(),
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val VERSION = "v1"
        const val SEPARATOR = "~"
    }
}
