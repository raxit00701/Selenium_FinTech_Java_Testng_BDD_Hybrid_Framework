package tests;

import org.testng.Assert;
import org.testng.annotations.Test;

import API.Login;
import base.Base;
import pages.Dashboard;
import utils.Videocapture.Video;
import utils.screenshot.Screenshot;

public class Login_Test extends Base {

    @Test(priority = 3)
    @Video
    @Screenshot
    public void dashboardValidationTest() {

        // 1. Bypass Login using login api (Instantly drops cookie & navigates to dashboard)
        Login.bypassLogin(driver);

        // 2. Validate Dashboard UI
        Dashboard dashboard = new Dashboard(driver);

        Assert.assertTrue(
                dashboard.isGetStartedDisplayed(),
                "'Get Started' heading is not displayed on the dashboard!"
        );

        // 3. Print result
        System.out.println(
                "Dashboard Validation PASSED: 'Get Started' heading is displayed."
        );
    }
}