import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class TestDb {
    public static void main(String[] args) {
        String url = "jdbc:mysql://localhost:3306/dsatracker?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        String[][] creds = {
            {"dsatracker", "dsatracker"},
            {"root", "root"},
            {"root", ""},
            {"dsatracker", ""}
        };
        for (String[] cred : creds) {
            try (Connection conn = DriverManager.getConnection(url, cred[0], cred[1]);
                 Statement stmt = conn.createStatement()) {
                System.out.println("Connected with " + cred[0]);
                ResultSet rs = stmt.executeQuery("SELECT url FROM job_sources");
                while(rs.next()) {
                    System.out.println("DB_URL: " + rs.getString("url"));
                }
                return;
            } catch(Exception e) {
                // ignore
            }
        }
        System.out.println("Could not connect");
    }
}
