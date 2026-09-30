import java.util.regex.*;

public class TestWorkday {
    public static void main(String[] args) {
        String[] urls = {
            "https://ncr.wd1.myworkdayjobs.com/ext_apac?Location_Country=c4f78be1a8f14da0ab49ce11",
            "https://ncratleos.wd1.myworkdayjobs.com/ext_apacatleos?Location_Country=c4f78be1a8f14d",
            "https://sec.wd3.myworkdayjobs.com/Samsung_Careers",
            "https://dentsuaegis.wd3.myworkdayjobs.com/en-US/DAN_GLOBAL/userHome"
        };
        Pattern WORKDAY_URL = Pattern.compile(
            "([a-z0-9_-]+)\\.(wd\\d+)\\.myworkdayjobs\\.com(?:/[a-z]{2}-[A-Z]{2})?/([a-zA-Z0-9_-]+)",
            Pattern.CASE_INSENSITIVE);
            
        for (String url : urls) {
            Matcher m = WORKDAY_URL.matcher(url);
            if (m.find()) {
                System.out.println("MATCHED: " + url);
                System.out.println("  tenant: " + m.group(1));
                System.out.println("  instance: " + m.group(2));
                System.out.println("  site: " + m.group(3));
            } else {
                System.out.println("FAILED: " + url);
            }
        }
    }
}
