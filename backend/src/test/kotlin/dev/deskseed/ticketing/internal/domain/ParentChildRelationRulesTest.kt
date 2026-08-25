package dev.deskseed.ticketing.internal.domain

import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.util.UUID

@dev.deskseed.testsupport.category.FastTest
class ParentChildRelationRulesTest {
    @Test
    fun `self link is rejected`() {
        val ticketId = UUID.randomUUID()

        assertThatIllegalArgumentException().isThrownBy {
            ParentChildRelationRules.requireValid(
                sourceTicketId = ticketId,
                targetTicketId = ticketId,
                sourceAlreadyHasParent = false,
                targetAlreadyHasParent = false,
                wouldCreateCycle = false,
            )
        }
    }

    @Test
    fun `depth greater than one and second parent are rejected`() {
        assertThatIllegalArgumentException().isThrownBy {
            ParentChildRelationRules.requireValid(
                sourceTicketId = UUID.randomUUID(),
                targetTicketId = UUID.randomUUID(),
                sourceAlreadyHasParent = true,
                targetAlreadyHasParent = false,
                wouldCreateCycle = false,
            )
        }
        assertThatIllegalArgumentException().isThrownBy {
            ParentChildRelationRules.requireValid(
                sourceTicketId = UUID.randomUUID(),
                targetTicketId = UUID.randomUUID(),
                sourceAlreadyHasParent = false,
                targetAlreadyHasParent = true,
                wouldCreateCycle = false,
            )
        }
    }

    @Test
    fun `cycle candidate is rejected`() {
        assertThatIllegalArgumentException().isThrownBy {
            ParentChildRelationRules.requireValid(
                sourceTicketId = UUID.randomUUID(),
                targetTicketId = UUID.randomUUID(),
                sourceAlreadyHasParent = false,
                targetAlreadyHasParent = false,
                wouldCreateCycle = true,
            )
        }
    }
}
