package sift.http;

import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import sift.tracker.TrafficHistory;

import javax.swing.SwingUtilities;

public class TrafficHttpHandler implements HttpHandler {
    // 过滤纯静态资源,保留 JS 和业务包
    private static final String STATIC_EXT =
        ".*\\.(css|png|jpg|jpeg|gif|ico|woff|woff2|ttf|eot|svg|mp4|mp3|pdf)(\\?.*)?$";

    private final TrafficHistory history;

    public TrafficHttpHandler(TrafficHistory history) {
        this.history = history;
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent request) {
        return RequestToBeSentAction.continueWith(request);
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived response) {
        String method = response.initiatingRequest().method();
        if ("OPTIONS".equalsIgnoreCase(method)) return ResponseReceivedAction.continueWith(response);

        String url = response.initiatingRequest().url();
        if (!url.matches(STATIC_EXT)) {
            String host = response.initiatingRequest().httpService().host();
            String path = response.initiatingRequest().path();
            SwingUtilities.invokeLater(() ->
                history.addEntry(method, host, path, response.initiatingRequest(), response));
        }
        return ResponseReceivedAction.continueWith(response);
    }
}
