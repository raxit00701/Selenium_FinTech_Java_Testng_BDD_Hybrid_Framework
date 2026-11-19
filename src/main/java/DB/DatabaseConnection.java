
package DB;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import io.github.cdimascio.dotenv.Dotenv;

public final class DatabaseConnection {

    private static final Dotenv dotenv = Dotenv.configure()
            .directory("src/main/java/env")
            .filename(".env")
            .ignoreIfMissing()
            .load();

    private static final String DB_URL = dotenv.get("DB_URL");
    private static final String DB_USERNAME = dotenv.get("DB_USERNAME");
    private static final String DB_PASSWORD = dotenv.get("DB_PASSWORD");

    private DatabaseConnection() {
        // Prevent object creation
    }

    public static Connection getConnection() throws SQLException {

        if (DB_URL == null || DB_USERNAME == null || DB_PASSWORD == null) {
            throw new IllegalStateException(
                    "Database configuration is missing. Check src/main/java/env/.env"
            );
        }

        return DriverManager.getConnection(
                DB_URL,
                DB_USERNAME,
                DB_PASSWORD
        );
    }
}
