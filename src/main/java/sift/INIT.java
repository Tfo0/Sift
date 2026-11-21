package sift;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import sift.http.TrafficHttpHandler;
import sift.tab.SiftTabPanel;
import sift.tracker.TrafficHistory;

public class INIT implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Sift");
        api.logging().logToOutput("Sift 已加载。");

        TrafficHistory history = new TrafficHistory();
        api.http().registerHttpHandler(new TrafficHttpHandler(history));
        api.userInterface().registerSuiteTab("Sift", new SiftTabPanel(api, history));
    }
}
