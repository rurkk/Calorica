package ru.calorica.catalog.application

import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import ru.calorica.platform.security.Actor
import ru.calorica.catalog.domain.CatalogFailure
import ru.calorica.catalog.persistence.CatalogLockSql
import java.util.UUID

class CatalogSnapshot internal constructor()
class CatalogWriteTransaction internal constructor() {
    internal var owner: UUID? = null
    internal var baseExclusive = false
    internal var baseShared = false
}

@Component
class CatalogTransactions(manager: PlatformTransactionManager, private val sql: CatalogLockSql) {
    private val read = TransactionTemplate(manager).apply {
        isReadOnly = true; isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ; timeout = 8
    }
    private val write = TransactionTemplate(manager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED; timeout = 8
    }
    fun <T : Any> snapshot(block: (CatalogSnapshot) -> T): T {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            check(TransactionSynchronizationManager.isCurrentTransactionReadOnly() &&
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ)
            return block(CatalogSnapshot())
        }
        return read.execute { sql.limits(); block(CatalogSnapshot()) }!!
    }
    fun <T : Any> mutation(block: (CatalogWriteTransaction) -> T): T {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Use the existing catalog write capability" }
        return write.execute { sql.limits(); block(CatalogWriteTransaction()) }!!
    }
    internal fun requireSnapshot(snapshot: CatalogSnapshot) {
        check(TransactionSynchronizationManager.isActualTransactionActive() &&
            TransactionSynchronizationManager.isCurrentTransactionReadOnly() &&
            TransactionSynchronizationManager.getCurrentTransactionIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ)
    }
    internal fun requireWrite(tx: CatalogWriteTransaction) {
        check(TransactionSynchronizationManager.isActualTransactionActive() &&
            !TransactionSynchronizationManager.isCurrentTransactionReadOnly() &&
            TransactionSynchronizationManager.getCurrentTransactionIsolationLevel() == TransactionDefinition.ISOLATION_READ_COMMITTED)
    }
}

/** Shared protocol for catalog, diary, units and labels; locks precede separate validation statements. */
@Component
class CatalogMutationLock(private val sql: CatalogLockSql, private val transactions: CatalogTransactions) {
    fun personal(actor: Actor, tx: CatalogWriteTransaction) {
        transactions.requireWrite(tx)
        check(!tx.baseExclusive && (tx.owner == null || tx.owner == actor.id))
        if (!tx.baseShared) { sql.sharedBase(); tx.baseShared = true }
        if (tx.owner == null) { sql.owner(actor.id); tx.owner = actor.id }
    }
    fun base(actor: Actor, tx: CatalogWriteTransaction) {
        transactions.requireWrite(tx)
        if (actor.role.name != "ADMIN") CatalogFailure.forbidden()
        check(!tx.baseShared && tx.owner == null)
        if (!tx.baseExclusive) { sql.exclusiveBase(); tx.baseExclusive = true }
    }
}
