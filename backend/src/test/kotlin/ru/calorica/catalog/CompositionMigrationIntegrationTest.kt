package ru.calorica.catalog

import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

@Tag("integration")
class CompositionMigrationIntegrationTest {
    @Test fun `T05 database upgrades to graph preserving previous checksums manual nutrition and seed rows`() {
        val original=requireNotNull(System.getenv("DB_URL"))
        val user=requireNotNull(System.getenv("DB_USERNAME")); val password=requireNotNull(System.getenv("DB_PASSWORD"))
        val name="graph_upgrade_"+UUID.randomUUID().toString().replace("-","")
        val url=original.replace(Regex("/[^/?]+(?=\\?|$)"),"/$name");require(url!=original)
        DriverManager.getConnection(original,user,password).use { admin ->
            admin.createStatement().use { it.execute("CREATE DATABASE $name") }
            try {
                fun migrate(changelog: String) {
                    val db=DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(DriverManager.getConnection(url,user,password)))
                    db.defaultSchemaName="public"
                    Liquibase(changelog,ClassLoaderResourceAccessor(),db).use { it.validate();it.update(Contexts(),LabelExpression()) }
                }
                migrate("db/changelog/composition-previous.yaml")
                val before=DriverManager.getConnection(url,user,password).use { connection -> connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT id,md5sum FROM public.databasechangelog ORDER BY id").use { result ->
                        buildList { while(result.next()) add(result.getString(1) to result.getString(2)) }
                    }
                } }
                migrate("db/changelog/composition-test.yaml");migrate("db/changelog/composition-test.yaml")
                DriverManager.getConnection(url,user,password).use { connection -> connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT id,md5sum FROM public.databasechangelog WHERE id <> '040-composition' ORDER BY id").use { result ->
                        val after=buildList { while(result.next()) add(result.getString(1) to result.getString(2)) }
                        assertThat(after).isEqualTo(before)
                    }
                    statement.executeQuery("SELECT count(*) FROM calorica.products WHERE owner_id IS NULL AND manual_calories IS NOT NULL AND manual_yield_factor IS NULL").use {
                        it.next();assertThat(it.getInt(1)).isEqualTo(50)
                    }
                    statement.executeQuery("SELECT count(*) FROM public.databasechangelog WHERE id='040-composition'").use { it.next();assertThat(it.getInt(1)).isEqualTo(1) }
                    // Previous leaf-only read/write statements continue to operate after 040.
                    statement.executeUpdate("UPDATE calorica.products SET manual_calories=manual_calories WHERE owner_id IS NULL")
                    statement.executeQuery("SELECT count(*) FROM calorica.product_components").use { it.next();assertThat(it.getInt(1)).isZero() }
                } }
            } finally { admin.createStatement().use { it.execute("DROP DATABASE $name") } }
        }
    }
}
