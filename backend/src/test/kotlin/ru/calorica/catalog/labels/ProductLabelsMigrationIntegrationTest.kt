package ru.calorica.catalog.labels

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
class ProductLabelsMigrationIntegrationTest {
    @Test fun `labels upgrade preserves old checksums and source data and supports repeat migration and cascaded links`() {
        val original=requireNotNull(System.getenv("DB_URL"))
        val user=requireNotNull(System.getenv("DB_USERNAME")); val password=requireNotNull(System.getenv("DB_PASSWORD"))
        val name="labels_upgrade_"+UUID.randomUUID().toString().replace("-","")
        val url=original.replace(Regex("/[^/?]+(?=\\?|$)"),"/$name"); require(url!=original)
        DriverManager.getConnection(original,user,password).use { admin ->
            admin.createStatement().use { it.execute("CREATE DATABASE $name") }
            try {
                fun migrate(changelog:String) {
                    val db=DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(DriverManager.getConnection(url,user,password)))
                    db.defaultSchemaName="public"
                    Liquibase(changelog,ClassLoaderResourceAccessor(),db).use { it.validate();it.update(Contexts(),LabelExpression()) }
                }
                migrate("db/changelog/product-labels-previous.yaml")
                val before=DriverManager.getConnection(url,user,password).use { connection -> connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT id,md5sum FROM public.databasechangelog ORDER BY id").use { result ->
                        buildList { while(result.next()) add(result.getString(1) to result.getString(2)) }
                    }
                } }
                migrate("db/changelog/product-labels-test.yaml");migrate("db/changelog/product-labels-test.yaml")
                DriverManager.getConnection(url,user,password).use { connection -> connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT id,md5sum FROM public.databasechangelog WHERE id <> '060-product-labels' ORDER BY id").use { result ->
                        assertThat(buildList { while(result.next()) add(result.getString(1) to result.getString(2)) }).isEqualTo(before)
                    }
                    statement.executeQuery("SELECT count(*) FROM calorica.products WHERE owner_id IS NULL AND manual_calories IS NOT NULL").use {
                        it.next();assertThat(it.getInt(1)).isEqualTo(50)
                    }
                    statement.executeQuery("SELECT count(*) FROM calorica.product_labels").use { it.next();assertThat(it.getInt(1)).isEqualTo(8) }
                    statement.executeQuery("SELECT count(*) FROM public.databasechangelog WHERE id='060-product-labels'").use { it.next();assertThat(it.getInt(1)).isEqualTo(1) }
                    statement.executeUpdate("""INSERT INTO calorica.product_label_links(product_id,label_id)
                        SELECT p.id,l.id FROM calorica.products p CROSS JOIN calorica.product_labels l LIMIT 1""")
                    statement.executeUpdate("DELETE FROM calorica.product_labels WHERE id IN (SELECT label_id FROM calorica.product_label_links)")
                    statement.executeQuery("SELECT count(*) FROM calorica.product_label_links").use { it.next();assertThat(it.getInt(1)).isZero() }
                } }
            } finally { admin.createStatement().use { it.execute("DROP DATABASE $name") } }
        }
    }
}
