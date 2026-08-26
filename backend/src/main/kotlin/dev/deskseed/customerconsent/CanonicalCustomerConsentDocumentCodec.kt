package dev.deskseed.customerconsent

import dev.deskseed.knowledge.CanonicalKnowledgeDocument
import dev.deskseed.knowledge.CanonicalKnowledgeDocumentCodec
import dev.deskseed.knowledge.CanonicalKnowledgeDocumentValidator
import dev.deskseed.knowledge.InvalidKnowledgeDocumentException
import dev.deskseed.knowledge.KnowledgeBlock
import dev.deskseed.knowledge.ValidatedKnowledgeDocument
import tools.jackson.databind.JsonNode
import java.nio.charset.StandardCharsets

class InvalidCustomerConsentDocumentException(message: String) : IllegalArgumentException(message)

/**
 * Consent-specific adapter over the storage-neutral knowledge document contract.
 * It deliberately narrows the block allowlist and applies publication limits before JDBC persistence.
 */
class CanonicalCustomerConsentDocumentCodec(
    private val codec: CanonicalKnowledgeDocumentCodec = CanonicalKnowledgeDocumentCodec(),
    private val validator: CanonicalKnowledgeDocumentValidator = CanonicalKnowledgeDocumentValidator(),
) {
    fun decode(node: JsonNode): CanonicalKnowledgeDocument = translateFailure {
        canonicalize(codec.decode(node))
    }

    fun encode(document: CanonicalKnowledgeDocument): Map<String, Any> = translateFailure {
        codec.encode(canonicalize(document))
    }

    fun validateDraft(document: CanonicalKnowledgeDocument): ValidatedKnowledgeDocument = translateFailure {
        val canonical = canonicalize(document)
        if (canonical.blocks.size > MAX_BLOCKS) {
            throw InvalidCustomerConsentDocumentException("Customer consent document block count is invalid")
        }
        validator.validate(canonical)
    }

    fun validateForPublish(document: CanonicalKnowledgeDocument): ValidatedKnowledgeDocument {
        val validated = validateDraft(document)
        val codePointLength = validated.plainText.codePointCount(0, validated.plainText.length)
        val utf8Length = validated.plainText.toByteArray(StandardCharsets.UTF_8).size
        if (codePointLength > MAX_PUBLISHED_CHARACTERS || utf8Length > MAX_PUBLISHED_UTF8_BYTES) {
            throw InvalidCustomerConsentDocumentException("Published customer consent document text is too long")
        }
        return validated
    }

    private fun canonicalize(document: CanonicalKnowledgeDocument): CanonicalKnowledgeDocument =
        document.copy(blocks = document.blocks.map(::canonicalizeBlock))

    private fun canonicalizeBlock(block: KnowledgeBlock): KnowledgeBlock = when (block) {
        is KnowledgeBlock.Paragraph -> KnowledgeBlock.Paragraph(canonicalText(block.text))
        is KnowledgeBlock.Heading -> KnowledgeBlock.Heading(block.level, canonicalText(block.text))
        is KnowledgeBlock.ListBlock -> KnowledgeBlock.ListBlock(block.ordered, block.items.map(::canonicalText))
        is KnowledgeBlock.Callout -> KnowledgeBlock.Callout(canonicalText(block.text))
        is KnowledgeBlock.Quote -> KnowledgeBlock.Quote(canonicalText(block.text))
        KnowledgeBlock.Divider -> KnowledgeBlock.Divider
        is KnowledgeBlock.Link -> KnowledgeBlock.Link(canonicalText(block.text), block.url.trim())
        is KnowledgeBlock.Code,
        is KnowledgeBlock.Attachment,
        is KnowledgeBlock.Html,
        -> throw InvalidCustomerConsentDocumentException("Customer consent document block type is not allowlisted")
    }

    private fun canonicalText(value: String): String {
        if (value.any(Char::isISOControl) || '<' in value || '>' in value) {
            throw InvalidCustomerConsentDocumentException("Customer consent document text is unsafe")
        }
        return value.trim()
    }

    private fun <T> translateFailure(action: () -> T): T = try {
        action()
    } catch (exception: InvalidCustomerConsentDocumentException) {
        throw exception
    } catch (exception: InvalidKnowledgeDocumentException) {
        throw InvalidCustomerConsentDocumentException(exception.message ?: "Customer consent document is invalid")
    } catch (exception: IllegalArgumentException) {
        throw InvalidCustomerConsentDocumentException(exception.message ?: "Customer consent document is invalid")
    }

    private companion object {
        const val MAX_BLOCKS = 100
        const val MAX_PUBLISHED_CHARACTERS = 50_000
        const val MAX_PUBLISHED_UTF8_BYTES = 200_000
    }
}
