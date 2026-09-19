import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

public class TestPlaywright {
    public static void main(String[] args) {
        try (Playwright playwright = Playwright.create()) {
            System.out.println("Playwright installed successfully");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
