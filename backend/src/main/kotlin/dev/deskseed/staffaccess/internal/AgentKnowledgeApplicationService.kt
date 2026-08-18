package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContext
import dev.deskseed.knowledge.KnowledgeAgentReadContext
import dev.deskseed.knowledge.KnowledgeReading
import dev.deskseed.knowledge.KnowledgeSearchPage
import dev.deskseed.knowledge.KnowledgeSearchQuery
import dev.deskseed.knowledge.PublishedKnowledgeArticle
import dev.deskseed.ticketing.CommentVisibility
import dev.deskseed.ticketing.StaffTicketReadScope
import dev.deskseed.ticketing.StaffTicketReadStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
internal class AgentKnowledgeApplicationService(
    private val knowledge: KnowledgeReading,
    private val ticketStore: StaffTicketReadStore,
    private val writeAuthorizationPolicy: GroupOrAssigneeTicketWriteAuthorizationPolicy,
    private val readAuthorizationPolicy: AgentTicketReadAuthorizationPolicy,
) {
    @Transactional
    fun search(principal: StaffPrincipal, query: KnowledgeSearchQuery, context: CommandContext, sessionId: String): KnowledgeSearchPage =
        knowledge.searchForAgent(principal.knowledgeContext(context, sessionId), query)

    @Transactional
    fun article(principal: StaffPrincipal, articleSlug: String, context: CommandContext, sessionId: String): PublishedKnowledgeArticle =
        knowledge.getArticleForAgent(principal.knowledgeContext(context, sessionId), articleSlug)

    @Transactional
    fun suggestions(
        principal: StaffPrincipal,
        ticketNumber: Long,
        context: CommandContext,
        sessionId: String,
    ): KnowledgeSearchPage {
        val detail = ticketStore.findDetail(ticketNumber) ?: throw AgentTicketNotFoundException()
        val directGrant = writeAuthorizationPolicy.canUpdate(principal, detail.ticket.group?.id, detail.ticket.assignee?.id)
        if (!readAuthorizationPolicy.canRead(StaffTicketReadScope.ALL_TICKETS, directGrant, false)) {
            throw AgentTicketNotFoundException()
        }
        // Suggestions derive only from subject and bounded PUBLIC comments. INTERNAL notes never
        // cross this boundary, including the protected query audit payload.
        val publicContext = buildString {
            append(detail.ticket.subject)
            detail.comments.asSequence()
                .filter { it.visibility == CommentVisibility.PUBLIC }
                .map { it.body.trim() }
                .filter(String::isNotEmpty)
                .take(3)
                .forEach { append(' ').append(it) }
        }.take(512)
        return knowledge.suggestionsForAgent(principal.knowledgeContext(context, sessionId), ticketNumber, publicContext)
    }

    private fun StaffPrincipal.knowledgeContext(context: CommandContext, sessionId: String) = KnowledgeAgentReadContext(
        staffId = id,
        staffDisplayName = displayName,
        sessionId = sessionId,
        commandContext = context,
    )
}
