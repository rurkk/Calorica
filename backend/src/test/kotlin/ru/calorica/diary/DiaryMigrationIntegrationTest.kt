package ru.calorica.diary

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
class DiaryMigrationIntegrationTest {
    @Test fun `diary migration installs on empty database and upgrades existing account catalog without data loss`() {
        val original = requireNotNull(System.getenv("DB_URL"))
        val user = requireNotNull(System.getenv("DB_USERNAME"))
        val password = requireNotNull(System.getenv("DB_PASSWORD"))
        val name = "diary_upgrade_"+UUID.randomUUID().toString().replace("-","")
        val url = original.replace(Regex("/[^/?]+(?=\\?|$)"),"/$name")
        require(url != original)
        DriverManager.getConnection(original,user,password).use { admin ->
            admin.createStatement().use { it.execute("CREATE DATABASE $name") }
            try {
                fun migrate(file: String) {
                    val database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(
                        JdbcConnection(DriverManager.getConnection(url,user,password)))
                    database.defaultSchemaName = "public"
                    Liquibase(file,ClassLoaderResourceAccessor(),database).use { it.validate(); it.update(Contexts(),LabelExpression()) }
                }
                migrate("db/changelog/diary-previous.yaml")
                val account = UUID.randomUUID()
                DriverManager.getConnection(url,user,password).use { old ->
                    old.prepareStatement("INSERT INTO calorica.accounts(id,email,password_hash,created_at) VALUES(?,'preserved@example.org','unusable-test-hash',now())").use {
                        it.setObject(1,account); it.executeUpdate()
                    }
                }
                migrate("db/changelog/diary-test.yaml")
                migrate("db/changelog/diary-test.yaml")
                DriverManager.getConnection(url,user,password).use { current ->
                    current.prepareStatement("SELECT email FROM calorica.accounts WHERE id=?").use {
                        it.setObject(1,account); it.executeQuery().use { result -> check(result.next()); assertThat(result.getString(1)).isEqualTo("preserved@example.org") }
                    }
                    current.createStatement().use { statement ->
                        statement.executeQuery("SELECT count(*) FROM calorica.products WHERE owner_id IS NULL").use { it.next(); assertThat(it.getInt(1)).isEqualTo(50) }
                        statement.executeQuery("SELECT count(*) FROM calorica.meal_labels").use { it.next(); assertThat(it.getInt(1)).isEqualTo(4) }
                        statement.executeQuery("SELECT count(*) FROM public.databasechangelog WHERE id='030-diary'").use { it.next(); assertThat(it.getInt(1)).isEqualTo(1) }
                    }
                }
                // The test application above also covers a fresh current schema; validate this migration's install independently.
                DriverManager.getConnection(url,user,password).use { current ->
                    current.createStatement().use { it.execute("DROP SCHEMA calorica CASCADE"); it.execute("DROP TABLE public.databasechangelog"); it.execute("DROP TABLE public.databasechangeloglock") }
                }
                migrate("db/changelog/diary-test.yaml")
            } finally { admin.createStatement().use { it.execute("DROP DATABASE $name") } }
        }
    }
}
