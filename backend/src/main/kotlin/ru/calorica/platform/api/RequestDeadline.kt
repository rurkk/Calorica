package ru.calorica.platform.api

import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionExecution
import org.springframework.transaction.TransactionExecutionListener
import org.springframework.transaction.support.AbstractPlatformTransactionManager

object RequestDeadline {
    private val deadline = ThreadLocal<Long>()
    fun enter(nanos: Long) { deadline.set(nanos) }
    fun leave() { deadline.remove() }
    fun check() {
        val end = deadline.get() ?: return
        if (Thread.currentThread().isInterrupted || System.nanoTime() >= end) throw ApiFailure(503, "SERVICE_UNAVAILABLE")
    }
}

/** Stops an interrupted/expired HTTP worker from committing after its 503 has been delivered. */
@Component
class DeadlineTransactions : BeanPostProcessor {
    override fun postProcessAfterInitialization(bean: Any, name: String): Any {
        if (bean is AbstractPlatformTransactionManager) {
            bean.transactionExecutionListeners = bean.transactionExecutionListeners + object : TransactionExecutionListener {
                override fun beforeCommit(transaction: TransactionExecution) { RequestDeadline.check() }
            }
        }
        return bean
    }
}
