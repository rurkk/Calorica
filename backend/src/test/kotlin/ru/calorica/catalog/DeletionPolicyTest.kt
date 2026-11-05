package ru.calorica.catalog

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.calorica.catalog.domain.*
import java.util.UUID

class DeletionPolicyTest {
    private val owner = UUID.randomUUID()
    private fun node(ownerId: UUID? = owner, deleted: Boolean = false) = DeletionCandidate(UUID.randomUUID(), ownerId, deleted)
    private fun failure(status: Int, block: () -> Unit) {
        assertThatThrownBy(block).isInstanceOfSatisfying(CatalogFailure::class.java) { assertThat(it.status).isEqualTo(status) }
    }

    @Test fun `keep mode only changes target while cascade selects active parents deterministically`() {
        val target = node(); val parent = node(); val oldParent = node(deleted = true)
        val closure = listOf(parent, target, oldParent)
        assertThat(DeletionPolicy.targets(target.id, owner, DeletionMode.KEEP_REFERENCES, closure)).containsExactly(target.id)
        assertThat(DeletionPolicy.targets(target.id, owner, DeletionMode.CASCADE, closure))
            .containsExactlyElementsOf(listOf(target.id, parent.id).sortedBy(UUID::toString))
    }

    @Test fun `foreign parent IDs never enter preview and make cascade forbidden even if already deleted`() {
        val target = node(null); val baseParent = node(null); val privateParent = node(deleted = true)
        val closure = listOf(target, baseParent, privateParent)
        val preview = DeletionPolicy.preview(target.id, null, closure)
        assertThat(preview.cascadeAllowed).isFalse()
        assertThat(preview.affectedProductIds).containsExactlyInAnyOrder(target.id, baseParent.id)
        failure(403) { DeletionPolicy.targets(target.id, null, DeletionMode.CASCADE, closure) }
        assertThat(DeletionPolicy.targets(target.id, null, DeletionMode.KEEP_REFERENCES, closure)).containsExactly(target.id)
    }

    @Test fun `missing deleted foreign and wrong route target have indistinguishable not found failure`() {
        val private = node(); val base = node(null); val deleted = node(deleted = true)
        failure(404) { DeletionPolicy.preview(private.id, UUID.randomUUID(), listOf(private)) }
        failure(404) { DeletionPolicy.preview(private.id, null, listOf(private)) }
        failure(404) { DeletionPolicy.preview(base.id, owner, listOf(base)) }
        failure(404) { DeletionPolicy.preview(deleted.id, owner, listOf(deleted)) }
        failure(404) { DeletionPolicy.preview(UUID.randomUUID(), owner, emptyList()) }
    }

    @Test fun `modes are exact and preview permission is not reused after dependency change`() {
        assertThat(DeletionMode.parse("KEEP_REFERENCES")).isEqualTo(DeletionMode.KEEP_REFERENCES)
        assertThat(DeletionMode.parse("CASCADE")).isEqualTo(DeletionMode.CASCADE)
        listOf("", "cascade", "DELETE", " CASCADE").forEach { value -> failure(400) { DeletionMode.parse(value) } }
        val target = node(null)
        assertThat(DeletionPolicy.preview(target.id, null, listOf(target)).cascadeAllowed).isTrue()
        failure(403) { DeletionPolicy.targets(target.id, null, DeletionMode.CASCADE, listOf(target, node())) }
    }
}
