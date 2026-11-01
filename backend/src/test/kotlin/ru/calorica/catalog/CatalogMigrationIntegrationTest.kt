package ru.calorica.catalog

import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

@Tag("integration")
class CatalogMigrationIntegrationTest {
    @Test fun `catalog upgrades foundation and revalidates without modifying existing changesets`() {
        val original = requireNotNull(System.getenv("DB_URL"))
        val user = requireNotNull(System.getenv("DB_USERNAME"))
        val password = requireNotNull(System.getenv("DB_PASSWORD"))
        val name = "catalog_upgrade_" + UUID.randomUUID().toString().replace("-", "")
        val url = original.replace(Regex("/[^/?]+(?=\\?|$)"), "/$name")
        require(url != original)
        DriverManager.getConnection(original, user, password).use { admin ->
            admin.createStatement().use { it.execute("CREATE DATABASE $name") }
            try {
                fun migrate(changelog: String) {
                    val db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(
                        JdbcConnection(DriverManager.getConnection(url, user, password)))
                    db.defaultSchemaName = "public"
                    Liquibase(changelog, ClassLoaderResourceAccessor(), db).use {
                        it.validate(); it.update(Contexts(), LabelExpression())
                    }
                }
                migrate("db/changelog/catalog-previous.yaml")
                DriverManager.getConnection(url, user, password).use { old ->
                    old.createStatement().use { statement ->
                        statement.executeQuery("SELECT count(*) FROM public.databasechangelog").use { r -> r.next(); assertThat(r.getInt(1)).isEqualTo(1) }
                    }
                }
                migrate("db/changelog/db.changelog-master.yaml")
                migrate("db/changelog/db.changelog-master.yaml")
                DriverManager.getConnection(url, user, password).use { current ->
                    current.createStatement().use { statement ->
                        statement.executeQuery("SELECT count(*) FROM calorica.products WHERE owner_id IS NULL").use { r -> r.next(); assertThat(r.getInt(1)).isEqualTo(50) }
                        statement.executeQuery("SELECT count(*) FROM public.databasechangelog WHERE id='020-products'").use { r -> r.next(); assertThat(r.getInt(1)).isEqualTo(1) }
                        statement.executeQuery("SELECT count(*) FROM pg_indexes WHERE schemaname='calorica' AND tablename='products'").use { r -> r.next(); assertThat(r.getInt(1)).isGreaterThanOrEqualTo(4) }
                    }
                }
            } finally {
                admin.createStatement().use { it.execute("DROP DATABASE $name") }
            }
        }
    }
}
