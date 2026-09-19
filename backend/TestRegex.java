
import java.util.regex.*;
public class TestRegex {
    public static void main(String[] args) {
        Pattern JOB_URL_PATTERN = Pattern.compile("/job[s]?/|/career[s]?/|/apply|/position[s]?/|/opening[s]?/|/vacanc|/req/", Pattern.CASE_INSENSITIVE);
        String url = "https://careers.jio.com/frmfuncwisejob.aspx?func=098qkj0vwzk";
        System.out.println("Matches: " + JOB_URL_PATTERN.matcher(url).find());
    }
}

