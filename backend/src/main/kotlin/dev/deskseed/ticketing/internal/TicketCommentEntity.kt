package dev.deskseed.ticketing.internal

import dev.deskseed.ticketing.CommentAuthorType
import dev.deskseed.ticketing.CommentVisibility
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "ticket_comments")
internal class TicketCommentEntity(
    @Id
    val id: UUID,

    @Column(name = "ticket_id", nullable = false)
    val ticketId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "author_type", nullable = false, length = 30)
    val authorType: CommentAuthorType,

    @Column(name = "author_id")
    val authorId: UUID?,

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    val visibility: CommentVisibility,

    @Column(name = "body", nullable = false, columnDefinition = "text")
    val body: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
)
