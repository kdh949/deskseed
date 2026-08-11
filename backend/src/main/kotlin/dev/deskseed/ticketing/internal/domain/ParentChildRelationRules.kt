package dev.deskseed.ticketing.internal.domain

import java.util.UUID

internal object ParentChildRelationRules {
    fun requireValid(
        sourceTicketId: UUID,
        targetTicketId: UUID,
        sourceAlreadyHasParent: Boolean,
        targetAlreadyHasParent: Boolean,
        wouldCreateCycle: Boolean,
    ) {
        require(sourceTicketId != targetTicketId) { "A ticket cannot be related to itself" }
        require(!sourceAlreadyHasParent) { "A child ticket cannot create another child" }
        require(!targetAlreadyHasParent) { "A child ticket can have only one parent" }
        require(!wouldCreateCycle) { "A parent-child relation cannot create a cycle" }
    }
}
