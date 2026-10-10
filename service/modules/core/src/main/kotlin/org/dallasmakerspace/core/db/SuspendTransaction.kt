package org.dallasmakerspace.core.db

import kotlinx.coroutines.Dispatchers
import org.dallasmakerspace.core.DBMemberPortalConnection
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

suspend fun <T> suspendTransaction(block: Transaction.() -> T): T =
    newSuspendedTransaction(Dispatchers.IO, statement = block, db = DBMemberPortalConnection.db)
