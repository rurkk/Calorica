package ru.calorica.catalog.domain

import java.util.UUID

data class DeletionPreview(val productId: UUID, val cascadeAllowed: Boolean, val affectedProductIds: List<UUID>)

/** A deletion candidate carries no private product details or calculated nutrition. */
data class DeletionCandidate(val id: UUID, val ownerId: UUID?, val isDeleted: Boolean)

enum class DeletionMode {
    KEEP_REFERENCES, CASCADE;

    companion object {
        fun parse(value: String): DeletionMode = entries.firstOrNull { it.name == value }
            ?: CatalogFailure.badRequest()
    }
}

object DeletionPolicy {
    fun requireTarget(id: UUID, owner: UUID?, candidates: List<DeletionCandidate>) {
        val target = candidates.singleOrNull { it.id == id } ?: CatalogFailure.notFound()
        if (target.isDeleted || target.ownerId != owner) CatalogFailure.notFound()
    }

    fun preview(id: UUID, owner: UUID?, candidates: List<DeletionCandidate>): DeletionPreview {
        requireTarget(id, owner, candidates)
        return DeletionPreview(id, candidates.all { it.ownerId == owner }, candidates
            .filter { !it.isDeleted && it.ownerId == owner }.map { it.id }.sortedBy(UUID::toString))
    }

    fun targets(id: UUID, owner: UUID?, mode: DeletionMode, candidates: List<DeletionCandidate>): List<UUID> {
        requireTarget(id, owner, candidates)
        if (mode == DeletionMode.KEEP_REFERENCES) return listOf(id)
        val preview = preview(id, owner, candidates)
        if (!preview.cascadeAllowed) CatalogFailure.forbidden()
        return preview.affectedProductIds
    }
}
